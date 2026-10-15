# 系统架构总览

## 一、系统形态

Travel OS 是**单体同源**应用：一个 Spring Boot 进程同时承担两件事 ——
对外提供 `/api/**` REST 接口，以及把前端静态目录挂到根路径直接托管。

```
浏览器
  │  GET /trips/          → 静态页（由 Spring 直接返回 apps/web/trips/index.html）
  │  GET /api/trips       → JSON（由 Controller 返回）
  ▼
Spring Boot（单进程，默认 3001）
  ├── WebMvc 静态资源处理器 → apps/web/**
  ├── AuthInterceptor       → 解析 Bearer / Cookie，写入 UserContext
  ├── MyBatis-Plus 数据权限 → 自动为查询追加 userId / tripId 条件
  └── Controller → Service → Mapper → PostgreSQL
```

**为什么不用前端框架 / 不做构建**：本项目是私人长期使用的工具，功能页面数量固定、交互形态稳定。
去掉构建链后，改一个页面只需编辑一份 HTML 并刷新浏览器，部署也只是 `rsync` 一份目录 ——
代价是共享代码需要手工复用（已抽出 `assets/css/theme.css`、`assets/js/api.js`、`assets/js/amap.js`）。

## 二、服务端分层

包路径：`apps/server/src/main/java/com/travelos/`

| 包 | 职责 | 说明 |
| --- | --- | --- |
| `controller/` | REST 接口层 | 21 个 Controller，只做参数接收与响应组装，不含业务判断 |
| `service/` | 业务逻辑层 | 18 个 Service，承载全部业务规则与事务边界 |
| `mapper/` | 数据访问层 | MyBatis-Plus Mapper 接口，SQL 由框架或注解生成 |
| `entity/` | 数据实体 | 22 个实体，与数据库表一一对应（驼峰 ↔ 下划线自动映射） |
| `auth/` | 身份与登录态 | `AuthInterceptor` 拦截、`AuthService` 登录注册、`UserContext` 当前用户 |
| `trip/` | 行程级权限 | `@RequireTripRole` 注解 + `TripRoleAspect` 切面 + `TripPermission` 枚举 |
| `ai/` | AI 旅行助手 | `AiChatService` 会话编排、`LlmGatewayService` 调用 OpenAI 兼容接口 |
| `config/` | Spring 配置 | 静态资源托管、CORS、MyBatis-Plus、数据权限、种子管理员、定时任务 |
| `common/` | 通用设施 | 异常与全局处理、JSON、JWT、日期、ID 生成、`.env` 加载、图片工具 |

**依赖方向单向**：`controller → service → mapper → entity`。
横切关注点（认证、权限、数据隔离、异常、审计）通过拦截器与 AOP 织入，不污染业务代码。

## 三、请求链路（以「查询行程费用」为例）

```
GET /api/trips/{tripId}/expenses
  │
  ├─ AuthInterceptor            解析 Authorization: Bearer <token>（缺失时回落 HttpOnly Cookie）
  │                             → 校验签名与有效期 → 加载用户 → 校验账户状态
  │                             → 写入 UserContext（ThreadLocal）
  │
  ├─ TripExpenseController      取当前用户，调用 service
  │
  ├─ TripExpenseService         requireTripMember(tripId, userId) —— 非成员直接 403
  │                             TripScopeContext 标记当前行程，供数据权限层使用
  │
  ├─ MyBatis-Plus 数据权限      TripScopeDataPermissionHandler 自动为 SQL 追加属主条件
  │
  └─ 返回 JSON（含 summary 聚合与最新结算快照）
```

## 四、认证与权限模型

### 认证（你是谁）

- **双通道令牌**：`Authorization: Bearer <token>` 优先，HttpOnly Cookie 兜底。
  静态站跨域场景用 Bearer（不受 Cookie 限制）；服务端渲染场景可继续用 Cookie。
- JWT 由 `common/JwtUtil` 签发与校验，有效期由 `JWT_EXPIRES_IN` 控制（默认 7 天）。
- 账户状态校验：`active` / `deleted`（软删）/ `disabled`，非 active 一律拒绝。

### 权限（你能做什么）

权限模型是**「行程成员制」+「行级属主」**的两层结构：

1. **行程维度**：所有业务数据都挂在 Trip 之下，访问前必须通过
   `TripMemberService.requireTripMember(tripId, userId)` 校验调用者是该行程成员。
   4 种角色：`owner` / `admin` / `member` / `viewer`。
2. **操作维度**：需要更细粒度控制时用 `@RequireTripRole({...})` 注解，
   由 `TripRoleAspect` 在方法执行前校验角色。
3. **行维度**：`TripScopeDataPermissionHandler` 在数据访问层自动追加属主条件，
   杜绝「越权读他人数据」的遗漏。

逐模块的隔离现状与改造依据见 `data-isolation.md`。

## 五、静态资源托管机制

`config/StaticResourceConfig` 用两种手段把静态目录做成可直接访问的站点：

1. `addResourceHandlers("/**")` → 指向 `apps/web/`，让 `/trips/index.html` 可被直接请求
2. `addViewControllers` → 扫描静态目录下的子目录，为每个含 `index.html` 的目录注册
   `/trips/` → `forward:/trips/index.html` 的目录索引兜底（Spring 静态资源本身不带目录索引）

目录解析顺序：`-Dtravelos.static.dir=...` / `application.yml` 的 `travelos.static.dir` 优先；
未配置时按当前工作目录自动探测 `apps/web`、`../web`、`../../web`，
从而同时兼容「项目根运行 jar」「apps/server 下跑 Maven」「target 目录内运行」三种方式。

## 六、后台任务

| 任务 | 触发 | 职责 |
| --- | --- | --- |
| `BookingReminderScheduler` | 定时 | 扫描临近出发的预订，按预订的提醒设置推送通知 |
| `UserCleanupScheduler` | 定时（默认关闭） | 清理软删用户及其数据，由 `app.user-cleanup.enabled` 控制 |
| `SeedRunner` | 启动时一次 | 首次启动创建种子管理员账号（已存在则跳过） |

## 七、外部依赖

| 依赖 | 用途 | 降级行为 |
| --- | --- | --- |
| PostgreSQL 16 | 唯一持久化存储 | 无 |
| 高德地图 JS API | 地图展示与驾车路线规划 | 路线规划失败 → 前端降级为虚线直连 |
| 高德地图 Web 服务 API | 地点搜索联想 | Key 未配置 → 接口返回 503，前端降级为纯手工输入 |
| LLM（OpenAI 兼容） | AI 旅行助手 | 三项配置任一为空 → 功能自动关闭 |
| 本地字体（内嵌） | 行程 PDF 导出的中文渲染 | 无（随包分发，不依赖系统字体） |

## 八、部署拓扑

```
GitHub push(main)
   → Actions：mvn verify 构建 jar
   → jar 上传腾讯云 COS（全球加速端点）
   → 服务器经内网拉取 jar（避免跨境慢链路）
   → 重放 apps/server/db/migration/*.sql
   → rsync apps/web/ → /var/www/travel-os/
   → systemctl restart travel-os-api
   → 健康检查失败则自动回滚到上一版 jar
```

服务器侧：Nginx 监听 80，`/api/` 反代到本机 3000，其余走静态文件；
systemd 托管后端进程。细节见 `deploy/服务器初始化手册.md`。

> 端口差异：本地 `.env` 中 `APP_PORT=3001`（配合 `start.cmd`），
> 生产 `deploy/app.env.example` 中为 `3000`（配合 Nginx 反代）。由环境变量控制，代码无需改动。
