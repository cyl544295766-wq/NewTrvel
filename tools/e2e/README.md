# 端到端验证脚本（tools/e2e）

用 Playwright 驱动**真实 Chrome**，对每个前端页面跑一遍真实操作链路：
真实登录 → 真实 API 调用 → 真实 DOM 断言 → 截图，跑完自动清理测试数据。

这些脚本是重构期间的验收手段，也可作为改动后的回归手段。

## 前置条件

1. **服务端已启动**并监听 3001（`start.cmd`，或 `java -jar apps/server/target/travel-os-api-0.1.0.jar`）
2. **PostgreSQL 容器运行中**，且库内已有种子行程 `cmrykzwh90000fmvg8xr7bclf`（「赤峰之旅」）
3. **登录态缓存** `D:/tmp/login.json` 存在（含 `token` 与 `user` 字段）。
   缺失时先跑一次 `verify-account.cjs`，它会在末尾重新登录并刷新该文件。
4. 截图输出目录 `D:/tmp/<page>-shots/` 需要可写（脚本自动创建）

脚本**不依赖项目内的 node_modules** —— 通过 `NODE_PATH` 复用托管 Node 工作区里的
`playwright-core`，并用 `channel: 'chrome'` 启动本机已安装的 Chrome。

## 运行方式

```bash
NODE="C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"
NMD="C:/Users/15003/.workbuddy/binaries/node/workspace/node_modules"

# 单个脚本
NODE_PATH="$NMD" "$NODE" tools/e2e/verify-trips.cjs

# 全部脚本（串行）
for f in tools/e2e/verify-*.cjs; do
  echo "=== $f ==="
  NODE_PATH="$NMD" "$NODE" "$f" || echo "!! $f 失败"
done
```

所有脚本约定：**全部断言通过**时以退出码 0 结束并打印 `=== ALL PASS ===`，
否则打印 `FAIL: <原因>` 并以退出码 1 结束。

## 脚本清单

| 脚本 | 覆盖页面 | 关键断言 |
| --- | --- | --- |
| `verify-trip-detail.cjs` | `/trip-detail/` | 登机牌票面四字段、8 个模块磁贴、成员 tab、天气降级、移动端 |
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
| `verify-account.cjs` | `/account/` | 资料与 `/auth/me` 一致、导出 JSON 真实下载、危险区双条件门禁、退出登录 |
| `verify-map.cjs` | `/map/` | 高德地图渲染、标点与真实驾车路线、按天聚焦、底图切换、侧栏折叠 resize、移动端抽屉 |

## 数据卫生约定

脚本会**自行创建**测试数据（行程 / 费用 / 清单 / 照片 / 游记 / 预订 / 通知等），
并在断言结束后**逐条删除**，不留残留。

例外与注意事项：

- `verify-notifications.cjs` 通过 `docker exec travel-os-postgres psql` 直插通知，
  测试数据用专属标题标识，结束时精确删除；「清除已读」按钮只断言存在、不点击（避免误删真实通知）
- `verify-account.cjs` 的「注销账户」只验证门禁三态，**从不提交**
- `verify-expenses.cjs` 的「确认结算」会落快照并发通知，故不点击
- 若某次运行中途异常退出，可能留下带「验证」前缀的测试数据，重新运行脚本会自动清理或需手动检查
