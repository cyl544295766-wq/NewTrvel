# 编码风格

## 服务端（Java）

- **单向依赖**：`controller → service → mapper → entity`。Controller 只做参数接收与响应组装，
  不写业务判断；Service 不直接拼 SQL。
- **构造器注入**：依赖统一 `private final` + 构造器注入，不用字段注入。
- **校验前置**：Service 方法开头先做空值与格式校验；业务规则失败抛 `ApiException`，
  由 `GlobalExceptionHandler` 统一转成 JSON 错误响应，不返回裸异常。
- **金额只用 `BigDecimal`**：禁止 `double` / `float` 参与金额运算；
  分摊尾差由付款人承担（见 `TripExpenseService`）。
- **权限就近放置**：行程成员校验写在 Service 方法开头（`requireTripMember`），
  需要角色区分时用 `@RequireTripRole({...})` 注解，不在 Controller 里散落判断。
- **行程上下文显式标记**：进入行程范围时用 `TripScopeContext` 标记，
  供数据权限层自动追加属主条件，不靠参数层层透传。
- **属主以服务端为准**：创建带属主的内容时一律写 `@CurrentUser`，
  忽略前端传入的 `uploaderId` / `authorId` / `creatorId`。

## 前端（HTML / CSS / JavaScript）

- **零构建**：每个页面是独立的 `index.html`，样式内联在 `<style>`，逻辑内联在 `<script>`；
  不做模块打包，改完刷新即可。
- **共享资源只有三个入口**：
  - `assets/css/theme.css` —— 设计令牌与基础样式
  - `assets/js/api.js` —— API 客户端与登录态
  - `assets/js/amap.js` —— 地图加载器
  其余代码保持页面内自包含。
- **样式一律走 CSS 变量**（`--brand`、`--card-shadow`、`--r-card` 等），不写魔法色值与尺寸。
- **网络请求统一走封装**：`apiGet / apiPost / apiPut / apiPatch / apiDelete`，
  它们自动附加 `Authorization: Bearer` 并在 401/403 时清理登录态。
- **错误面向用户**：统一用 `humanError(err)` 转成中文短句，不把原始异常抛到界面上。
- **登录态守卫**：页面启动先调 `requireLogin()`，未登录自动跳登录页并记录回跳地址。
- **并发加载用 `Promise.allSettled`**：单个接口失败不能让整页白屏，
  失败区域降级为「暂无数据」或引导文案。
- **无障碍基线**：可点击元素用 `<button>` / 带 `role`；状态用 `aria-pressed`、`aria-expanded`、
  `aria-current` 表达；纯装饰元素加 `aria-hidden`。

## 数据库

- 表名、列名 `snake_case`；见 `docs/conventions/naming.md`。
- 迁移脚本**只增不改，且必须幂等**（详见 `apps/server/db/migration/README.md`）。
- 协作产生的内容表带行级属主列，危险操作（改、删）校验「属主本人或 owner/admin」。

## 提交前自检

```bash
# 服务端：编译 + 测试
cd apps/server && mvn -B verify

# 前端：无需构建，改动后直接刷新对应页面即可
# 需要回归时跑端到端脚本（见 tools/e2e/README.md）
```
