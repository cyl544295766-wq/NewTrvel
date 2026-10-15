# Travel OS

[![CI/CD](https://github.com/cyl544295766-wq/NewTrvel/actions/workflows/deploy.yml/badge.svg)](https://github.com/cyl544295766-wq/NewTrvel/actions/workflows/deploy.yml)

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
│   │   │   ├── controller/          REST 接口层（21 个 Controller）
│   │   │   ├── service/             业务逻辑层（18 个 Service）
│   │   │   ├── mapper/              数据访问层（MyBatis-Plus Mapper）
│   │   │   ├── entity/              数据实体（22 张表）
│   │   │   ├── auth/                登录态与身份（AuthInterceptor / UserContext / CurrentUser）
│   │   │   ├── trip/                行程级权限（成员制校验 + @RequireTripRole 切面）
│   │   │   ├── ai/                  AI 旅行助手（OpenAI 兼容协议）
│   │   │   ├── config/              Spring 配置（静态资源托管 / CORS / 拦截器注册）
│   │   │   └── common/              通用设施（异常、JSON、统一响应、.env 加载）
│   │   ├── src/main/resources/      application.yml、内嵌中文字体（PDF 导出用）
│   │   ├── db/migration/            数据库迁移脚本（按文件名顺序执行，只增不改）
│   │   ├── start-api.cmd            单独启动服务端（Maven）
│   │   └── pom.xml
│   └── web/                         ← 前端（纯 HTML + CSS + 原生 JS，无框架、无构建）
│       ├── assets/css/theme.css     全站设计令牌与基础样式
│       ├── assets/js/api.js         API 客户端与登录态管理
│       ├── assets/js/amap.js        高德地图 JS API 加载器
│       ├── assets/img/favicon.svg   站点图标
│       ├── <page>/index.html        各功能页（19 个，见下表）
│       └── index.html               入口跳转（→ /home/）
├── deploy/                          ← 生产部署：Nginx 站点、systemd 服务、环境变量模板、初始化手册
├── docs/                            ← 项目文档
│   ├── architecture/                架构总览、数据模型、接口设计、数据隔离
│   ├── conventions/                 编码风格、命名、Git 工作流
│   └── product/                     产品愿景、路线图
├── tools/e2e/                       ← 端到端验证脚本（Playwright，见其 README）
├── docker-compose.yml               ← 本地 PostgreSQL
├── start.cmd                        ← 一键启动（Docker + 服务端 + 打开浏览器）
└── .env                             ← 本地配置（端口、数据库、密钥；服务端启动时自动读取）
```

## 前端页面一览

| 分组 | 页面 | 路径 | 职责 |
| --- | --- | --- | --- |
| 入口 | 总览 | `/home/` | 工作台：下一段旅程登机牌、统计四联、最近行程与快捷入口 |
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
| 系统 | 通知中心 | `/notifications/` | 通知聚合、筛选、已读管理 |
| | 账户设置 | `/account/` | 账户资料、数据导出、退出与注销 |

## 技术栈

| 层 | 选型 |
| --- | --- |
| 服务端 | Java 17、Spring Boot 2.7、MyBatis-Plus、Spring AOP、Caffeine（本地缓存） |
| 数据库 | PostgreSQL 16（Docker），迁移为纯 SQL 脚本顺序执行 |
| 认证 | JWT；`Authorization: Bearer` 优先、HttpOnly Cookie 兜底 |
| 文档与导出 | PDFBox（行程 PDF 导出）、Thumbnailator + TwelveMonkeys（图片处理，含 WebP） |
| 前端 | 原生 HTML / CSS / JavaScript，无框架、无构建步骤 |
| 地图 | 高德地图 JS API 2.0（Key 见 `apps/web/assets/js/amap.js`） |
| 运维 | Docker、Nginx、systemd、GitHub Actions（构建 → COS 中转 → 服务器内网拉取） |

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

## 部署

生产环境为 Ubuntu + Nginx + systemd + Docker PostgreSQL，由 GitHub Actions 自动化部署：

1. 构建后端 jar（`mvn -B verify`）
2. jar 经腾讯云 COS 中转，服务器从内网拉取（避免跨境慢链路）
3. 按文件名顺序重放 `apps/server/db/migration/*.sql`（脚本必须幂等）
4. `rsync` 发布前端静态目录到 `/var/www/travel-os/`
5. 重启 systemd 服务并做健康检查，失败自动回滚到上一个 jar

完整步骤与所需 Secrets 见 `deploy/服务器初始化手册.md`。

## 文档

| 文档 | 内容 |
| --- | --- |
| `docs/architecture/overview.md` | 系统架构、模块职责、请求链路、认证与权限模型 |
| `docs/architecture/data-model.md` | 数据模型：表清单、关系、枚举值与设计约束 |
| `docs/architecture/api-design.md` | REST 接口约定：路径、响应、错误码、分页 |
| `docs/architecture/data-isolation.md` | 数据隔离改造清单与逐模块依据 |
| `docs/conventions/` | 编码风格、命名约定、Git 工作流 |
| `docs/product/` | 产品愿景与路线图 |
| `docs/项目功能说明.md` | 面向使用者的功能说明 |
| `tools/e2e/README.md` | 端到端验证脚本的运行方式与覆盖范围 |

## 开发约定

- **Trip 是核心业务实体**，任何业务模块都必须直接或间接挂在 Trip 之下
- 实现功能前，先确定数据库、业务流程、API、页面结构
- 前端页面保持**零构建**：直接写 HTML/CSS/JS，服务端原样托管
- 数据库迁移**只增不改且必须幂等**（详见 `apps/server/db/migration/README.md`）
