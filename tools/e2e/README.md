# 端到端验证脚本（tools/e2e）

用 Playwright 驱动**真实 Chrome**，对每个前端页面跑一遍真实操作链路：
真实登录 → 真实 API 调用 → 真实 DOM 断言 → 截图，跑完自动清理测试数据。

这些脚本是重构期间的验收手段，也可作为改动后的回归手段。

## 前置条件

1. **本地服务端已启动**并监听 3001（`start.cmd`，或按交接文档 §9.6：
   `java -Dfile.encoding=UTF-8 -Dtravelos.static.dir=<repo>\apps\web -jar apps\server\target\travel-os-api-0.1.0.jar`，
   **工作目录必须是仓库根**，`.env` 靠它被 EnvLoader 读到）。健康检查 `GET /api/health`。
2. **PostgreSQL 容器运行中**：`docker ps` 里有 `travel-os-postgres` —— 清理阶段用
   `docker exec … psql` 按准确 ID 硬删夹具。
3. **运行环境**（脚本不依赖项目内 node_modules）：
   - Node（本机托管版）：`C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe`
   - `NODE_PATH` 指向托管工作区：`C:/Users/15003/.workbuddy/binaries/node/workspace/node_modules`
     （复用其中的 `playwright-core`）
   - 本机已安装 Chrome：脚本用 `chromium.launch({ channel: 'chrome', headless: true, ... })`
4. **登录态缓存** `D:/tmp/login.json`（含 `token` 与 `user` 字段）——**只有老脚本需要**；
   JWT 7 天过期，过期先刷新：

   ```bash
   NODE="C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"
   # 用 admin 登录并覆写 login.json（仅本地；密码见 .env 的 SEED_ADMIN_PASSWORD）
   curl -s -X POST http://127.0.0.1:3001/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin123"}' > /tmp/login-resp.json
   # 提取 token/user 写入 D:/tmp/login.json（或直接跑一次 verify-account.cjs 让它自己刷新）
   ```

   **`verify-home.cjs` / `verify-map.cjs` 不需要它**：自注册独立测试账号（每跑一次注册 1 个）。

## 环境变量

| 变量 | 适用 | 默认 | 说明 |
| --- | --- | --- | --- |
| `E2E_ORIGIN` | verify-home / verify-map | `http://127.0.0.1:3001` | 测试地址。**只允许回环地址**（127.0.0.1/localhost）——这两个脚本会创建并硬删数据，非本地目标直接拒绝运行，防止误打生产 |
| `E2E_SHOTS` | verify-home / verify-map | `D:/tmp/home-shots`、`D:/tmp/map-shots` | 截图输出目录（自动创建） |
| `E2E_FAULT` | verify-map | 不设置（关闭） | **可控故障注入，仅作用于测试上下文、不改生产逻辑**，供 `selftest-map-classify.cjs` 验证失败分类：`sdk-abort`（高德请求全部 abort）/ `route-fail`（驾车 JSONP 注入 10021 限流应答）/ `route-hang`（`/route` API 挂起且无任何错误事件）/ `biz-throw`（注入含「网络」+amap.js 路径的页面 TypeError）。支持逗号组合（如 `biz-throw,sdk-abort`，验证业务/环境证据分别保留）。设置了未知值直接拒绝运行 |
| `PROD_ORIGIN` / `PROD_USER` / `PROD_PASS` | verify-prod | 生产 `http://140.143.166.155` | **指向生产且会创建临时数据**的验收脚本，属既有约定；默认批量运行时已排除它，只在明确要验收生产时手动执行 |

## 运行方式

```bash
NODE="C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"
NMD="C:/Users/15003/.workbuddy/binaries/node/workspace/node_modules"
export NODE_PATH="$NMD"

# 单个脚本
"$NODE" tools/e2e/verify-home.cjs
"$NODE" tools/e2e/verify-map.cjs

# 全部本地脚本（串行；默认排除指向生产的 verify-prod）
for f in tools/e2e/verify-*.cjs; do
  case "$f" in *verify-prod.cjs) echo "=== skip $f（生产目标，手动验收用） ==="; continue;; esac
  echo "=== $f ==="
  "$NODE" "$f" || echo "!! $f 失败"
done
```

脚本约定：**全部断言通过**时退出码 0 并打印 `=== ALL PASS ===`；否则退出码 1 并打印
`=== FAILED ===` 或 `=== ENV FAILURE ===`。

### 失败分类（verify-home / verify-map，证据驱动、超时不武断归因）

> 分类唯一实现在 `tools/e2e/lib/error-classify.cjs`，verify-home / verify-map 运行时与
> `selftest-map-classify.cjs` 的分类回归共用同一份逻辑（不复制实现自证）。

- **分类优先级**（先命中先归类）：
  1. 显式网络错误码 `net::ERR_*` → 环境；
  2. SDK 全局缺失 `AMap is not defined` → 环境（SDK 加载失败证据，优先于异常名）；
  3. 页面运行时异常 **TypeError / ReferenceError 等（按首行异常名识别）→ 业务**——消息含
     `网络/域名/加载失败/exceeded/amap` 等普通字样**不**改变分类，字样本身不是证据；
  4. 窄化 SDK/Key/限流特征 → 环境（`高德地图网络连接失败`、`高德地图脚本加载异常`、
     `高德插件加载超时`、`未配置高德地图 JS API Key`——只由 `assets/js/amap.js` 加载路径发出；
     `INVALID_USER*`/`USERKEY`/`CUQPS`/`10021`——高德服务端错误码）；
  5. 其余（普通 Error、console 报错等）→ 业务。
- **业务断言失败（biz）**：页面脚本异常（按上述优先级）、页面 `/api/` 请求返回 4xx/5xx、
  DOM 状态不符合预期、业务断言失败。
- **环境失败（env）**：只认明确证据——网络错误码、SDK 加载失败、Key 无效/白名单、高德限流、
  页面自身降级提示（「未返回/虚线」）、高德域名请求失败（请求来源证据；URL 已脱敏不含 query，
  防 Key/签名泄漏）。
- **原因未定的超时（undet）**：既无环境证据也无业务证据的超时（如 API 挂起、页面静默卡住）——
  单独计数、单独标注 `UNDETERMINED FAILURE`，保持非零退出，**不武断归为环境问题**。
- 业务与环境证据**分别保留、互不覆盖**：同时存在时按业务失败归类（汇总层业务失败优先定
  `FAILED` 标签），环境计数照常输出——业务异常绝不输出纯 `ENV FAILURE`。
- 任一非 0 都不通过（exit 1）：汇总行打印 `业务断言失败 / 环境失败(SDK/网络/Key，不计通过) /
  原因未定的超时(不计通过) / 清理` 四项。
- **清理失败**：夹具没删干净或残留核验非 0 → 退出码 1，明确报错，绝不静默；**所有失败场景
  （含故障注入）同样执行清理，且任何失败都不打印 ALL PASS**。
- 两者都用**有界条件等待**（`waitFor` 轮询 + 超时报错）替代固定 sleep；超时即失败，不会无限挂起。
- 分类正确性由 `selftest-map-classify.cjs` 回归验证：Part 1 直接调用 `lib/error-classify.cjs`
  覆盖业务关键词/QuotaExceededError/amap 路径/SDK 失败/Key/限流/混合保留等 10 个案例，
  Part 2 用五种可控故障注入端到端验证（见下表）。

## 本地批量回归入口

```bash
NODE="C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"
export NODE_PATH="C:/Users/15003/.workbuddy/binaries/node/workspace/node_modules"
E2E_ORIGIN=http://127.0.0.1:3001 bash tools/e2e/run-all.sh                 # 全量（排除 verify-prod）
E2E_ORIGIN=http://127.0.0.1:3001 bash tools/e2e/run-all.sh verify-home verify-map   # 指定脚本
```

- 串行执行；`E2E_ORIGIN` 仅允许回环（非本地直接拒绝）；每脚本退出码/结果汇总，
  失败明细在各脚本日志（`E2E_WORK_DIR`，默认 /d/tmp/e2e-run-*）。
- **注册限流感知**：脚本输出含 `Retry-After` 时自动有界等待（单次 ≤620s）重试一次，
  仍 429 则记为 RATE-LIMITED（不算业务通过）；不要为跑批量放宽产品限流。
- 截图按脚本隔离在 `$E2E_SHOTS_BASE/<脚本名>/`（默认 D:/tmp/e2e-shots/）。
- 共享夹具/清理工具在 `lib/fixture.cjs`（注册、API 助手、有界等待、按准确 ID 清理器
  + 残留核验 + 磁盘文件范围守卫）；错误分类共享在 `lib/error-classify.cjs`。

## 脚本清单

| 脚本 | 覆盖页面 | 关键断言 |
| --- | --- | --- |
| `verify-home.cjs` | `/home/` | 自注册账号两阶段：**空态**（倒计时空文案/四区空态/示例行程区/统计全0）→ 播种后**数据态**（问候与日期、倒计时+12天、登机牌左栏、统计四联与 `/api/dashboard` 一致、地图面板徽标与标点、行程速览/花费/照片/游记按实际数据、`/files/` 缩略图 Cookie 通道可加载、**行程入口真实跳转**：点击行程行 + 键盘 Enter 激活 → `/trip-detail/?id=<夹具ID>` 且详情页渲染夹具标题、顶栏高亮与未读角标、断点 1120/980/720、移动端顶栏导航）；`#railToggle` 侧栏折叠已随顶栏化改版移除（无替代交互），改为断言顶栏导航现状 |
| `verify-map.cjs` | `/map/` | 自注册账号+3天×2点夹具（逐天 edit-lock 取还）：**里程碑A 地图可见**（遮罩撤除/画布/标题/点数）与**里程碑B 路线完成**（独立等待：无「路线计算中」、里程/时长出数、跨天转场 >800km、无降级）分别断言；地点联动高亮、按天聚焦（位移+非当天置灰+重置按钮）、重置聚焦回全览、**回到全览**（原 `#railToggle` resize 断言的替代交互）、底图切换、移动端抽屉（**真实几何断言**：过渡到目标态后采样，展开=有效尺寸+整体在视口+锚定底部12px+核心内容与关闭按钮在视口内可见，收起=opacity:0/pointer-events:none+实际移出视口，截图在稳定后生成）、无参数行程选择器；不再依赖已删除的侧栏按钮与种子行程 |
| `verify-trip-detail.cjs` | `/trip-detail/` | 登机牌票面四字段、模块磁贴、成员 tab、天气降级、移动端 |
| `verify-trip-form.cjs` | `/trip-form/` | 新建链路（预览实时更新 → 创建 → 跳详情）、编辑链路（预填 → 改状态 → 保存） |
| `verify-itinerary.cjs` | `/itinerary/` | 勾选完成 → 进度环与总览联动、加地点、行内编辑、上下移 reorder、天标题 |
| `verify-expenses.cjs` | `/expenses/` | 三种分摊模式（均分 / 按比例 / 按金额）、尾差、报表环形与柱状、结算转账方案 |
| `verify-packing.cjs` | `/packing/` | 类别模板物品自动填充、勾选进度、100% 触发 READY 印章、复制清单 |
| `verify-documents.cjs` | `/documents/` | 真实文件上传、到期预警色带（soon / expired）、提醒开关、筛选与搜索、预览浮层 |
| `verify-photos.cjs` | `/photos/` | 多选上传、按天分组、拼贴/网格视图切换、lightbox 改说明、封面唯一性 |
| `verify-journals.cjs` | `/journals/` | 卷首大卡、草稿徽章、状态与心情筛选、抽屉预填编辑、删除 |
| `verify-journal-detail.cjs` | `/journal-detail/` | 正文解析（小标题/首字下沉/拉引句）、目录锚点滚动、上下篇导航 |
| `verify-bookings.cjs` | `/bookings/` | 5 类型票面、航线拆分、状态章推断、后端派生提醒分钟、编辑预填 |
| `verify-vouchers.cjs` | `/vouchers/` | 凭证时间轴、现场速查信息格、自绘 QR、复制确认码、打印 |
| `verify-stats.cjs` | `/stats/` | 年份切换、年度对比柱、月度分布、分类环形、点表格行下钻 |
| `verify-notifications.cjs` | `/notifications/` | 未读/重要徽章、时间分组、类型筛选、link 改写、单条与全部已读 |
| `verify-account.cjs` | `/account/` | 资料与 `/auth/me` 一致、导出 JSON 真实下载、危险区双条件门禁、退出登录（结束后刷新 login.json） |
| `verify-prod.cjs` | 生产全链路 | **指向生产**的历史验收脚本（31 项）；会创建临时行程并自清理；默认批量运行时排除 |
| `selftest-map-classify.cjs` | verify-map 分类自检 | **Part 1 分类回归**：直接 require `lib/error-classify.cjs`（scripts 实际使用的唯一实现）断言 10 个案例——业务 TypeError 含「网络」/含「域名·加载失败」/QuotaExceededError（含 exceeded）/含 amap.js 路径 → 业务；SDK 失败（`AMap is not defined`、amap.js 加载字符串）、`net::ERR_*`、Key 无效、10021 限流 → 环境；混合证据分别保留。**Part 2 故障注入**：五种 `E2E_FAULT` 模式各跑一遍 verify-map（SDK 失败/页面降级 → `ENV FAILURE` 且业务失败 0；无证据挂起 → `UNDETERMINED FAILURE`；页面 TypeError → `FAILED`；`biz-throw,sdk-abort` 混合 → `FAILED` 且业务/环境均 ≥1），逐一核对退出码非零、不打印 ALL PASS、夹具清理成功 |

> 另有自注册型专项脚本（friends / day-lock / chat-collab / journal-visibility / square /
> stats-budget / today-brief / admin / pet / pet-global / integrations / logout 等），
> 同样遵守「自行建号、按 ID 清理」的约定。

## 数据卫生约定

- **verify-home / verify-map**：注册成功即记录用户 ID、建行程即记录行程/行程天 ID；
  无论成败都按这些**准确 ID** 硬删（不使用用户名前缀、不碰其他批次/个人数据），
  并做**残留核验**（trips/users/members/expenses|places/locks/notifications/file_metadata/磁盘文件
  必须归零），核验非 0 即判清理失败。`photos` 落盘缩略图（`data/files/<32hex>.png`）按
  `photos.url` 名单精确删除（模式守卫防路径逃逸）。
- 本地库缺表（如 `journal_edit_locks` 未建）时跳过并打印 `(skip)`——表不存在就不可能有本次数据；
  **表存在但删除失败仍然报错**。
- **注册限流**：`register-ip-max-attempts=10 / 600s`（IP 维度）。批量连跑会注册型脚本时
  可能撞 429（响应带 `Retry-After`）——等窗口过期再跑，**不要为了测试放宽产品限流**。
- 其他脚本沿用既有约定：自行创建测试数据、逐条删除；`verify-notifications` 的「清除已读」
  只断言不点击；`verify-account` 的「注销账户」只验门禁不提交；`verify-expenses` 的
  「确认结算」不点击。若某次运行中途异常退出，可能留下带「验证」前缀的测试数据，
  需按该脚本的 ID/标题手工核对（不要用前缀一把删）。
