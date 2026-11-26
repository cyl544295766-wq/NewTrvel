# Travel OS

[![CI](https://github.com/cyl544295766-wq/NewTrvel/actions/workflows/ci.yml/badge.svg)](https://github.com/cyl544295766-wq/NewTrvel/actions/workflows/ci.yml)

私人长期旅行管理平台：把每一次出行当作一张**登机牌**来签发、归档与回味。

**项目形态**：Spring Boot 服务端 + 纯静态前端（无前端构建步骤）。
服务端既提供 REST API，也直接托管前端静态目录 —— 同源部署，前端不存在跨域问题。

---

## 目录结构

```
NewTrevl/
├── apps/
│   ├── server/                      ← 服务端（Java 17 / Spring Boot + MyBatis-Plus）
│   │   ├── src/main/java/com/travelos/
│   │   │   ├── controller/          REST 接口层（31 个 Controller）
│   │   │   ├── service/             业务逻辑层（32 个 Service）
│   │   │   ├── mapper/              数据访问层（MyBatis-Plus Mapper）
│   │   │   ├── entity/              数据实体（31 张表）
│   │   │   ├── auth/                登录态与身份（AuthInterceptor / UserContext / 限流 AuthRateLimiter / huawei 华为账号登录）
│   │   │   ├── trip/                行程级权限（成员制校验 + @RequireTripRole 切面）
│   │   │   ├── ai/                  AI 旅行助手（会话编排 / 有界执行 AiTaskManager / 发送幂等 / 草稿应用）
│   │   │   ├── config/              Spring 配置（静态资源 / CORS / 拦截器 / 文件404 / 种子守卫）
│   │   │   └── common/              通用设施（异常、JSON、统一响应、文件统一错误、.env 加载、载荷取值 Payloads、分页 Pagination）
│   │   ├── src/main/resources/      application.yml、内嵌中文字体（PDF 导出用）
│   │   ├── db/migration/            数据库迁移脚本（按文件名顺序执行，只增不改）
│   │   ├── start-api.cmd            单独启动服务端（Maven）
│   │   └── pom.xml
│   └── web/                         ← 前端（纯 HTML + CSS + 原生 JS，无框架、无构建）
│       ├── assets/css/theme.css     全站设计令牌与基础样式
│       ├── assets/js/api.js         API 客户端与登录态管理
│       ├── assets/js/amap.js        高德地图 JS API 加载器
│       ├── assets/img/site-icon.png 站点图标
│       ├── <page>/index.html        各功能页（21 个，见下表）
│       └── index.html               入口跳转（→ /home/）
├── deploy/                          ← 生产部署：Nginx 站点、systemd 服务、环境变量模板、初始化手册
├── docs/                            ← 项目文档
│   ├── architecture/                架构总览、数据模型、接口设计、数据隔离、未接入清单
│   ├── conventions/                 编码风格、命名、Git 工作流
│   ├── product/                     产品愿景、路线图
│   └── 发布流水线.md                 发布流水线说明（本地发布 + CI 构建验证）
├── tools/e2e/                       ← 端到端验证脚本（Playwright，见其 README）
├── docker-compose.yml               ← 本地 PostgreSQL
├── start.cmd                        ← 一键启动（Docker + 服务端 + 打开浏览器）
└── .env                             ← 本地配置（端口、数据库、密钥；服务端启动时自动读取）
```

## 前端页面一览

| 分组 | 页面 | 路径 | 职责 |
| --- | --- | --- | --- |
| 入口 | 总览 | `/home/` | 工作台：下一段旅程、AI 今日简报、统计四联、AI 创建工具与最近内容 |
| 认证 | 登录 / 注册 | `/login/` | 账号登录与注册，签发访问令牌 |
| 主流程 | 我的旅行 | `/trips/` | 行程列表（登机牌卡片、状态筛选、搜索、收藏） |
| | 签发 / 编辑行程 | `/trip-form/` | 行程表单 + 实时登机牌预览（新建与编辑共用） |
| | 行程详情 | `/trip-detail/` | 行程总览、8 个功能模块入口、成员管理 |
| | 行程安排 | `/itinerary/` | 按天时间轴、地点增删改与排序、完成度进度 |
| | 行程地图 | `/map/` | 高德地图、每日驾车路线、按天聚焦 |
| 消费与准备 | 费用记账 | `/expenses/` | 记账、分摊（均分/比例/金额）、报表、结算 |
| | 打包清单 | `/packing/` | 分类清单、模板物品、勾选进度 |
| | 预订管理 | `/bookings/` | 机票/酒店/火车/门票/其他，提醒设置 |
| | 凭证速查 | `/vouchers/` | 出行现场速查凭证（大字信息格 + 二维码） |
| | 证件文档 | `/documents/` | 证件与票据归档、到期预警 |
| | 照片相册 | `/photos/` | 多选上传、按天分组、拼贴/网格、封面 |
| 记录与洞察 | 旅行游记 | `/journals/` | 游记列表与撰写（心情、关联照片） |
| | 游记阅读 | `/journal-detail/` | 沉浸式阅读排版、上下篇导航 |
| | 旅行统计 | `/stats/` | 年度维度统计与图表 |
| 社交 | 好友 | `/friends/` | 好友申请与列表、好友私信 |
| | 攻略广场 | `/square/` | 公开攻略浏览、一键复制为我的行程 |
| 系统 | 通知中心 | `/notifications/` | 通知聚合、筛选、已读管理 |
| | 账户设置 | `/account/` | 账户资料、数据导出、退出与注销 |
| | 管理后台 | `/admin/` | 平台管理（用户与系统设置） |

## 技术栈

| 层 | 选型 |
| --- | --- |
| 服务端 | Java 17、Spring Boot 2.7、MyBatis-Plus、Spring AOP |
| 数据库 | PostgreSQL 16（Docker），迁移为纯 SQL 脚本顺序执行（只增不改且幂等） |
| 认证 | JWT；`Authorization: Bearer` 优先、HttpOnly Cookie 兜底 |
| AI | OpenAI 兼容协议 `/chat/completions`（tools + 流式 SSE）；未配置 `llm.*` 自动降级关闭 |
| 文档与导出 | PDFBox（行程 PDF 导出）、Thumbnailator + TwelveMonkeys（图片处理，含 WebP） |
| 前端 | 原生 HTML / CSS / JavaScript，无框架、无构建步骤 |
| 地图 | 高德地图 JS API 2.0（Key 见 `apps/web/assets/js/amap.js`） |
| 运维 | Docker、Nginx、systemd、GitHub Actions（构建验证）；部署走本地一键发布 `deploy/publish.sh` |

## 安全与资源防护

围绕「同源托管 + 私人部署」的威胁面，已落地以下防线（全部有自动化测试覆盖，见下节）：

- **统一失败响应与登录防护**：业务错误统一 JSON（`statusCode/message/error`）；429 统一带 `Retry-After`；
  登录/注册按 IP+账号维度限流（`AuthRateLimiter`）、防枚举统一「用户名或密码错误」文案、
  生产环境默认/弱管理员密码启动即拒绝（`AdminPasswordGuard`，`seed.allow-default-admin-password` 仅本地）。
- **文件访问**：`/files/**` 走对象级授权（`FileAccessService` + `file_metadata` 表，属主/行程成员才可读）；
  非法路径、越权与不存在统一返回 JSON 404（`FileAwareErrorReportValve` + `FileErrorDispatchInterceptor`），
  不再泄漏容器默认 HTML/400；合法 GET/HEAD/Range/401 行为保持不变。
- **AI 发送链路**：`AiTaskManager` 组合准入——单用户并发 / 全局并发 / 等待队列 / 频率窗口 /
  **UTC 自然日每日预算**（`ai.concurrency.*`、`ai.rate-limit.*`、`ai.budget.*`，全部可配置，单实例进程内计数）；
  每次生成有总时限（`ai.task.timeout-ms`），客户端断开会把取消传播到模型 HTTP 连接与后续工具轮次。
- **AI 消息发送幂等**：普通与流式共享 `clientRequestId`（`ai_request_keys` 表 + `execution_token` 状态机，
  条件更新接管/回放/失败重试，保留期自动清理）。
- **AI 草稿应用互斥**：`pending → applying → applied / discarded` 全部为数据库条件更新；
  `applying` 带租约（`ai.draft.apply-stale-ms`，默认 60s）可条件接管，完成落账带执行令牌 fencing——
  并发应用/放弃只有一个赢家，业务写入 + 审计 + 终态在同一事务，旧执行者被接管后整体回滚不产生副作用。

## 本地运行

前置：JDK 17、Maven、Docker Desktop。

```bash
# 1. 启动数据库
docker compose up -d

# 2. 一键启动（推荐）：拉起容器 → 启动服务端 → 等待健康检查 → 打开浏览器
start.cmd
```

或手动启动服务端：

```bash
cd apps/server
mvn spring-boot:run
```

访问 <http://127.0.0.1:3001>，默认账号 `admin / admin123`。

> 端口、数据库地址、密钥等由**仓库根目录的 `.env`** 提供，服务端启动时通过
> `com.travelos.common.EnvLoader` 读入系统属性（查找顺序：`./.env` → `../.env` → `../../.env`）。
> 变量清单见 `deploy/app.env.example`。

## 测试与回归

```bash
# 后端全量（apps/server 下）：单元 + 真实 HTTP 集成（含模拟模型，不调用付费模型）
mvn -B verify                                  # 本地（Windows）基线：280 用例 / 0 失败 / 1 跳过（符号链接）

# 前端（仓库根目录）：通知流 + AI 发送策略；文档预览
node --test apps/web/tests/notifications-stream.test.cjs apps/web/tests/ai-send-policy.test.cjs   # 29
node apps/web/tests/doc-preview.test.cjs                                                      # 28 断言

# E2E 错误分类回归（纯分类，无浏览器/账号/数据库/网络依赖）
node tools/e2e/selftest-map-classify.cjs --classify-only
```

- 测试数据源与生产一致（真实 PostgreSQL），并发/幂等/租约类断言均基于数据库条件更新语义；
  AI 相关集成测试内嵌模拟模型服务，CI 不产生模型费用。
- surefire 配置（`pom.xml`）含**仅测试生效**的 Hikari 收敛（单池 ≤3、空闲收缩）——
  防止多个 `@SpringBootTest` 上下文累积连接打满本地 PG `max_connections=100`，勿删。

### CI 执行范围（.github/workflows/ci.yml）

- **后端 job**：Ubuntu runner 上的 **一次性 PostgreSQL 16 service 容器**（CI 专用账号
  `travel_os_ci` / 库 `travel_os_ci`，随 job 销毁，绝不连接本地或生产库）→ 有界等待就绪 →
  按 `apps/server/db/migration` 文件名顺序**全量重放迁移**（逐文件执行、`ON_ERROR_STOP=1`，
  任一 SQL 失败即工作流失败）→ JDK 17 + **Maven 3.9.11** `mvn -B verify`。
- **数据源配置来源**：CI checkout 无 `.env`（已 gitignore），EnvLoader 找不到 `.env` 即空转；
  数据源由 **`SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 环境变量**（Spring relaxed binding）
  提供——**只设 `DATABASE_URL` 环境变量不会生效**（EnvLoader 只解析来自 `.env` 文件的值）。
  CI 同时注入安全值：非默认种子口令 + `SEED_ALLOW_DEFAULT_ADMIN_PASSWORD=false`、
  独立 `JWT_SECRET` + `JWT_ALLOW_DEFAULT=false`、`FILES_DIR` 收敛到 runner 工作区。
- **前端 job**：Node 22 运行通知流/AI 发送策略/文档预览测试 + `selftest-map-classify.cjs
  --classify-only` 纯分类回归（不启动浏览器、不建账号、不连库）。
- **产物**：后端失败时上传 Surefire 报告（保留 7 天），不含 `.env`、密钥与数据目录。
- 依赖真实高德服务的完整地图 E2E 与浏览器故障注入自检（selftest 不带参数）**不在常规 CI 内**，
  仍按 `tools/e2e/README.md` 本地运行。
- **本地复现 CI**：`docker run -d --name ci-pg-verify -e POSTGRES_USER=travel_os_ci
  -e POSTGRES_PASSWORD=... -e POSTGRES_DB=travel_os_ci -p 127.0.0.1:15432:5432 postgres:16`
  → 按上法重放迁移 → 在 `apps/server` 下以
  `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15432/travel_os_ci?stringtype=unspecified
  SPRING_DATASOURCE_USERNAME=travel_os_ci SPRING_DATASOURCE_PASSWORD=... mvn -B verify` 复跑。

## 部署

生产环境为 Ubuntu + Nginx + systemd + Docker PostgreSQL。**部署唯一入口是本地一键发布**（零外购成本、同国链路快）：

```bash
bash deploy/publish.sh
```

构建 → 备份 → scp 直传 jar → 数据库迁移 → 发布前端 → 重启 + 健康检查（失败自动回滚）。
服务器连接配置写入 `deploy/.deploy.env`（已 gitignore），变量见脚本头部注释。

GitHub Actions（`.github/workflows/ci.yml`）只做构建验证（编译 + 测试 + 打包），push / PR 均不部署——
跨境直传实测限速 ~40KB/s（44MB jar 要 25 分钟以上），故不在线上链路部署。

## 文档

| 文档 | 内容 |
| --- | --- |
| `docs/architecture/overview.md` | 系统架构、模块职责、请求链路、认证与权限模型 |
| `docs/architecture/data-model.md` | 数据模型：表清单、关系、枚举值与设计约束 |
| `docs/architecture/api-design.md` | REST 接口约定：路径、响应、错误码、分页 |
| `docs/architecture/data-isolation.md` | 数据隔离改造清单与逐模块依据 |
| `docs/conventions/` | 编码风格、命名约定、Git 工作流 |
| `docs/product/` | 产品愿景与路线图 |
| `docs/发布流水线.md` | 发布流水线：本地一键发布、CI 构建验证、耗时实测与演进 |
| `docs/代码冗余*.md` | 冗余审查报告 / 删除决策 / 清理结果（2026-11-02 三部曲） |
| `docs/项目功能说明.md` | 面向使用者的功能说明 |
| `tools/e2e/README.md` | 端到端验证脚本的运行方式与覆盖范围 |

## 开发约定

- **Trip 是核心业务实体**，任何业务模块都必须直接或间接挂在 Trip 之下
- 实现功能前，先确定数据库、业务流程、API、页面结构
- 前端页面保持**零构建**：直接写 HTML/CSS/JS，服务端原样托管
- 数据库迁移**只增不改且必须幂等**（详见 `apps/server/db/migration/README.md`）
