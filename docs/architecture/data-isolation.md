# 数据隔离设计与改造记录

> 生成于 2026-10-13，基于 `apps/server` 重构「注册与登录」模块时的代码走查。
> 目标：为后续多用户数据隔离加固提供逐模块改造依据。

## 一、现状结论

当前的数据隔离采用 **「行程成员制」**：所有业务数据挂在 `trips` 之下，任何访问先经
`TripMemberService.requireTripMember(tripId, userId)` 校验当前用户是否为该行程成员。
因此 **行程维度** 的隔离已基本成立，缺的是「行内行级属主」与「全局越权防线」。

## 二、已有隔离能力（无需改动）

| 模块 | 隔离机制 | 状态 |
|---|---|---|
| AuthController / AuthInterceptor | 全局登录态 + 账户状态校验（active/deleted/disabled） | ✅ 本次已完成 |
| TripsService | `trip_members.user_id` 成员校验；列表仅返回本人参与的行程 | ✅ |
| TripMemberController | 成员/角色校验 | ✅ |
| TripDayController、TripPlaceController | requireTripMember / requireTripRole | ✅ |
| TripExpenseController | 成员校验 + 分摊成员必须属于本行程 | ✅ |
| PackingListController、PhotoController、TravelDocumentController、TripJournalController | requireTripMember（NotFoundException→Forbidden 转换） | ✅ |
| WeatherController、TripPdfController、TripsController#route | requireTripMember | ✅ |
| NotificationController | `notifications.user_id` 按当前用户过滤 | ✅（表自带 user_id） |
| TripPdfService.export | 导出前 requireTripMember | ✅ |

## 三、缺属主字段的数据表（建议新增列）

以下表只有 `trip_id` 外键、**没有行级属主字段**。在「协作」场景下无法回答
"这条数据是谁创建的"，也无法做"仅创建者可删"类约束：

| 表 | 建议新增列 | 说明 |
|---|---|---|
| `photos` | `uploader_id → users.id` | 照片上传者 |
| `travel_documents` | `uploader_id → users.id` | 文档上传者 |
| `trip_journals` | `author_id → users.id` | 游记作者 |
| `packing_lists` | `creator_id → users.id` | 清单创建者 |
| `trip_days` | （可选）`created_by → users.id` | 行程天多为系统生成，优先级低 |

对应 SQL（下一步迁移时使用）：

```sql
ALTER TABLE photos            ADD COLUMN IF NOT EXISTS uploader_id TEXT REFERENCES users(id);
ALTER TABLE travel_documents  ADD COLUMN IF NOT EXISTS uploader_id TEXT REFERENCES users(id);
ALTER TABLE trip_journals     ADD COLUMN IF NOT EXISTS author_id   TEXT REFERENCES users(id);
ALTER TABLE packing_lists     ADD COLUMN IF NOT EXISTS creator_id  TEXT REFERENCES users(id);
```

## 四、Controller / Mapper 需要补 `user_id` 过滤的位置

按「先读后写」原则列出需要追加行级过滤的点位（业务表结构变更后实施）：

| 位置 | 现状 | 需要改造 |
|---|---|---|
| `PhotoService.findAll/create/update` | 仅行程成员校验 | create 时写入 `uploaderId`；如需"仅上传者可删"，update/delete 增加 `photo.uploaderId == userId` 判断 |
| `TravelDocumentService.create/update/delete` | 仅行程成员校验 | 同上，写 `uploaderId` 并在危险操作时校验 |
| `TripJournalService.create/update/delete` | 仅行程成员校验 | 写 `authorId`；`delete` 建议仅作者或 owner/admin 可执行 |
| `PackingListService.create/delete` | 仅行程成员校验 | 写 `creatorId`；删除清单需 owner/admin/creator |
| `TripsService.findAll` | 成员 JOIN 过滤 | 保持现状即可；若新增"游客角色只读"策略，读接口按 `role != viewer` 限制写操作（部分已用 requireTripRole） |
| `TripMemberService.invite/remove` | editableTripRoles 校验 | 保持现状；owner 角色转移需新增专门接口并二次确认 |
| `DashboardService.findOne` | 按 memberships 聚合 | 已按用户聚合 ✅ |
| `TravelStatsService` | 按 memberships 聚合 | 已按用户聚合 ✅ |

## 五、全局防线（建议随下一次改造落地）

1. **MyBatis-Plus 拦截器兜底**：为 `trips` 相关查询统一注入 `trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = :me)`，防止未来某个 Mapper 忘记手写过滤。
2. **写操作角色矩阵**：owner/admin/member/viewer 四角色的读写矩阵目前分散在各 Service 常量中（`TripPermission.EDITABLE_ROLES`），建议收敛为统一注解 `@RequireTripRole(...)` + AOP。
3. **viewer 角色写拦截**：现在 viewer 在部分模块（如打包清单、游记）仍可写，需要统一收紧。
4. **审计日志**：注销、成员移除、角色变更等敏感操作建议落审计表（`audit_logs`）。

## 六、本次已完成的铺垫

- `users.deleted_at` 列 + 迁移脚本 `apps/server/db/migration/V20261013_01__add_users_deleted_at.sql`
- `AuthInterceptor` 全局校验账户状态（注销用户 JWT 立即失效）
- `UserCleanupScheduler`（默认关闭）预留注销 30 天后物理清理扩展点
- 前端 `http.ts` 统一 401 → 跳登录页（携带回跳地址）

## 已完成改造（第二阶段，2026-10-13）

本次按「数据隔离加固」提示词落地以下内容（迁移脚本 `db/migration/V20261013_02__add_row_owners.sql`）：

1. **行级属主列**：photos.uploader_id / travel_documents.uploader_id / trip_journals.author_id / packing_lists.creator_id（含 FK 与索引），脚本幂等可重放
2. **存量回填**：四表属主回填为行程 owner（trip_members role='owner'），回填后无 NULL 行；代码对 NULL 属主仍保留「任何成员可改」兼容逻辑
3. **写入端强制属主**：四个模块的 create 接口一律以 `@CurrentUser` 写入属主，忽略前端传入的 uploaderId/authorId/creatorId 字段（已实测伪造无效）
4. **危险操作属主校验**：
   - 照片、文档：update/delete 需「上传者本人」或 owner/admin
   - 游记：update/delete/照片排序需「作者本人」或 owner/admin
   - 打包清单：delete 整张清单需「创建者本人」或 owner/admin
   - 非属主且非管理员操作返回 403「仅XX本人或行程管理员可执行该操作」
5. **viewer 写拦截收紧**：PackingList / TripJournal / Photo / TravelDocument 四模块所有写接口统一走 `TripPermission.EDITABLE_ROLES`，viewer 一律 403
6. **前端对齐**：Photos / Documents / PackingLists / Journals 四页面对 viewer 隐藏主写入口（新建/上传按钮）

### 验收记录（实测）

- 迁移脚本连续执行两次均成功，回填后四表 NULL 属主计数为 0
- 成员 B 上传照片（伪造 uploaderId）→ DB 属主仍为 B；viewer C 删除/创建照片均 403；owner 删除成功
- 游记 author_id 同上；viewer 修改 403、owner 修订 200、作者本人删除 200
- 打包清单 creator_id 同上；viewer 删除/创建 403、创建者本人删除 200
- 文档 uploader_id 同上；viewer 删除 403、上传者本人删除 200
- `mvn -B verify` 通过；`vue-tsc -b` 通过

### 遗留项（下一阶段）

- MyBatis-Plus 全局拦截器兜底注入
- `@RequireTripRole` 注解 + AOP 角色矩阵收敛
- 审计日志表（audit_logs）
- owner 角色转移接口
- 打包清单物品级（packing_items）暂无独立属主，跟随清单创建者策略

## 已完成改造（第三阶段：全局防线，2026-10-13）

1. **MyBatis-Plus 拦截器兜底**（`TripScopeDataPermissionHandler` + `DataPermissionInterceptor`）：
   - trips 按 `id`、trip_days/places/expenses/photos/travel_documents/trip_journals/packing_lists/trip_weather 按 `trip_id` 注入「当前用户是行程成员」子查询；
     packing_items / trip_expense_shares / trip_journal_photos 无 trip_id，经父表嵌套子查询过滤；
     notifications 按 `user_id` 过滤
   - `users` / `trip_members` / `audit_logs` / `system_settings` 不拦截（trip_members 的跨成员管理由手写校验覆盖）
   - 上下文桥接：`AuthInterceptor` 认证成功写 `TripScopeContext`（ThreadLocal），`afterCompletion` 必清理；
     无上下文（种子/定时任务/未登录）自动不拦截；显式绕过用 `TripScopeContext.runBypassed(...)`（SeedRunner、UserCleanupScheduler 已用）
   - 单元测试 `TripScopeDataPermissionHandlerTest`（10 个用例：成员/非成员/绕过/无上下文/嵌套表/防注入）先行，接入后集成回归验证
   - 拦截器有效性实测（临时 debug 端点，验收后已删除）：owner/成员 `found:true`、非成员 `found:false`、未登录 401
2. **@RequireTripRole 注解 + AOP**（`TripRoleAspect`）：从方法参数解析 tripId、复用 requireTripRole、404→403 保留；
   已迁移 Trip/TripDay/TripPlace/TripExpense/TripMember/Photo/TravelDocument/PackingList/TripJournal 九个控制器的全部写接口；
   `requireOwnerOrManager` 保留方法调用形式
3. **审计日志**（迁移 `V20261013_03__add_audit_logs.sql`，幂等）：
   - 已落审计：账户注销、成员邀请/移除/角色变更、owner 转移、照片/文档/游记/清单删除
   - 查询接口 `GET /api/trips/{tripId}/audit-logs`（分页、时间倒序），owner/admin 200、普通成员/viewer 403
   - detail 仅含业务上下文（用户名/标题/角色），无密码与 token
4. **owner 转移** `POST /api/trips/{tripId}/transfer-owner`：
   - 仅 owner 可调（非 owner 403）；目标须为成员（否则 400）、不能转给自己（400）
   - 事务内角色互换（目标→owner、原 owner→admin），带「原 owner 角色未变」条件更新的并发保护；
     目标已是 owner 时幂等返回；落审计 + 向目标写通知（「你已成为旅行所有者」）

### 验收记录（第三阶段，实测）

- 迁移两次重放 0 错误；audit_logs 与索引就位
- 回归红线：行程 CRUD/生成天数/行程点/费用/照片/文档/游记/清单/仪表盘/统计/通知 全部 200，行为与改造前一致
- 拦截器证明：非成员经无手写校验端点查询 `found:false`
- 注解收口：grep 无 requireEditableMember / 手写 requireTripRole 调用残留（仅 TripMemberService 定义与切面）
- 审计五类操作全部落表；member(owner) 查询 200、viewer 403
- 转移：owner→member 角色互换 + 通知 + 审计；非 owner 403；非成员 400；自转 400；重复调用 403 无脏数据
- `mvn -B verify` 通过（10 单测全绿）

### 剩余遗留项

- 审计日志前端界面
- 超级管理员/平台级后台
- packing_items 物品级独立属主（维持跟随清单策略）

## 已完成改造（第三阶段：全局防线，2026-10-13）

1. **MyBatis-Plus 拦截器兜底**（`TripScopeDataPermissionHandler` + `DataPermissionInterceptor`）：
   - trips 按 `id`、trip_days/places/expenses/photos/travel_documents/trip_journals/packing_lists/trip_weather 按 `trip_id` 注入「当前用户是行程成员」子查询；
     packing_items / trip_expense_shares / trip_journal_photos 无 trip_id，经父表嵌套子查询过滤；
     notifications 按 `user_id` 过滤
   - `users` / `trip_members` / `audit_logs` / `system_settings` 不拦截（trip_members 的跨成员管理由手写校验覆盖）
   - 上下文桥接：`AuthInterceptor` 认证成功写 `TripScopeContext`（ThreadLocal），`afterCompletion` 必清理；
     无上下文（种子/定时任务/未登录）自动不拦截；显式绕过用 `TripScopeContext.runBypassed(...)`（SeedRunner、UserCleanupScheduler 已用）
   - 单元测试 `TripScopeDataPermissionHandlerTest`（10 个用例：成员/非成员/绕过/无上下文/嵌套表/防注入）先行，接入后集成回归验证
   - 拦截器有效性实测（临时 debug 端点，验收后已删除）：owner/成员 `found:true`、非成员 `found:false`、未登录 401
2. **@RequireTripRole 注解 + AOP**（`TripRoleAspect`）：从方法参数解析 tripId、复用 requireTripRole、404→403 保留；
   已迁移 Trip/TripDay/TripPlace/TripExpense/TripMember/Photo/TravelDocument/PackingList/TripJournal 九个控制器的全部写接口；
   `requireOwnerOrManager` 保留方法调用形式
3. **审计日志**（迁移 `V20261013_03__add_audit_logs.sql`，幂等）：
   - 已落审计：账户注销、成员邀请/移除/角色变更、owner 转移、照片/文档/游记/清单删除
   - 查询接口 `GET /api/trips/{tripId}/audit-logs`（分页、时间倒序），owner/admin 200、普通成员/viewer 403
   - detail 仅含业务上下文（用户名/标题/角色），无密码与 token
4. **owner 转移** `POST /api/trips/{tripId}/transfer-owner`：
   - 仅 owner 可调（非 owner 403）；目标须为成员（否则 400）、不能转给自己（400）
   - 事务内角色互换（目标→owner、原 owner→admin），带「原 owner 角色未变」条件更新的并发保护；
     目标已是 owner 时幂等返回；落审计 + 向目标写通知（「你已成为旅行所有者」）

### 验收记录（第三阶段，实测）

- 迁移两次重放 0 错误；audit_logs 与索引就位
- 回归红线：行程 CRUD/生成天数/行程点/费用/照片/文档/游记/清单/仪表盘/统计/通知 全部 200，行为与改造前一致
- 拦截器证明：非成员经无手写校验端点查询 `found:false`
- 注解收口：grep 无 requireEditableMember / 手写 requireTripRole 调用残留（仅 TripMemberService 定义与切面）
- 审计五类操作全部落表；member(owner) 查询 200、viewer 403
- 转移：owner→member 角色互换 + 通知 + 审计；非 owner 403；非成员 400；自转 400；重复调用 403 无脏数据
- `mvn -B verify` 通过（10 单测全绿）

### 剩余遗留项

- 审计日志前端界面
- 超级管理员/平台级后台
- packing_items 物品级独立属主（维持跟随清单策略）
