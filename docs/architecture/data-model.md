# 数据模型

数据库为 PostgreSQL 16，共 **22 张表**，全部围绕 `trips` 组织。实体类见
`apps/server/src/main/java/com/travelos/entity/`，建表脚本见 `apps/server/db/migration/`。

## 一、关系总览

```
users ──┬── tripmembers ────────────────── trips ──┬── trip_days ── trip_places
        │            (owner/admin/member/viewer)   │        │
        │                                          │        └── trip_weather
        │                                          │
        │                                          ├── photos            （可挂 trip_day / trip_place）
        │                                          ├── travel_documents
        │                                          ├── packing_lists ── packing_items
        │                                          ├── trip_journals ── trip_journal_photos
        │                                          ├── bookings
        │                                          ├── trip_expenses ── trip_expense_shares
        │                                          ├── trip_budgets
        │                                          └── trip_settlements
        │
        ├── notifications
        ├── audit_logs
        └── ai_conversations ── ai_messages

system_settings（全局键值配置，独立于用户）
```

**核心约束**：除 `users` / `system_settings` 外，所有业务数据都必须通过 `trip_id`
挂在某个行程之下 —— 这是权限模型与数据隔离的基础。

## 二、表分组说明

### 账户与系统

| 表 | 说明 | 关键列 |
| --- | --- | --- |
| `users` | 用户账户 | `username`（唯一）、`display_name`、`password_hash`、`status`、`deleted_at`（软删） |
| `system_settings` | 系统级键值配置 | `key`（唯一）、`value` |
| `audit_logs` | 敏感操作审计 | `user_id`、`action`、`target_type`、`target_id`、`detail` |
| `notifications` | 站内通知 | `user_id`、`type`、`priority`、`is_read`、`link`、`metadata`、`expires_at` |
| `ai_conversations` / `ai_messages` | AI 助手会话与消息 | `conversation_id`、`role`、`content` |

### 行程核心

| 表 | 说明 | 关键列 |
| --- | --- | --- |
| `trips` | 行程主表 | `title`、`destination`、`start_date`、`end_date`、`status`、`budget`、`cover_image_url`、`owner_id`、`is_favorite`、`archived_at`、`deleted_at` |
| `trip_members` | 行程成员与角色 | `trip_id`、`user_id`、`role`、`display_name` |
| `trip_days` | 行程天 | `trip_id`、`day_index`、`date`、`title` |
| `trip_places` | 行程地点（时间轴节点） | `trip_day_id`、`name`、`type`、`latitude`、`longitude`、`start_time`、`end_time`、`estimated_cost`、`is_completed`、`sort_order` |
| `trip_weather` | 行程天气 | `trip_id`、`date`、温度与天气描述字段 |

### 消费与结算

| 表 | 说明 | 关键列 |
| --- | --- | --- |
| `trip_expenses` | 费用记录 | `trip_id`、`title`、`amount`（Decimal）、`category`、`payer_user_id`、`spent_at`、`notes` |
| `trip_expense_shares` | 费用分摊明细 | `expense_id`、`user_id`、`share_amount`、`ratio` |
| `trip_budgets` | 行程预算（按类别） | `trip_id`、`category`、`amount`、`estimated_amount` |
| `trip_settlements` | 结算快照 | `trip_id`、`transfers`（JSON）、`created_by`、`created_at` |

### 行程内容

| 表 | 说明 | 关键列 | 行级属主 |
| --- | --- | --- | --- |
| `photos` | 照片 | `url`（data URL）、`caption`、`shot_at`、`is_cover`、`trip_day_id`、`trip_place_id` | `uploader_id` |
| `travel_documents` | 证件与票据 | `url`、`type`、`title`、`expired_at`、`is_reminder`、`notes` | `uploader_id` |
| `packing_lists` | 打包清单 | `name`、`category` | `creator_id` |
| `packing_items` | 清单条目 | `packing_list_id`、`name`、`quantity`、`is_packed`、`order_index` | — |
| `trip_journals` | 游记 | `title`、`content`、`mood`、`is_draft`、`trip_day_id`、`trip_place_id` | `author_id` |
| `trip_journal_photos` | 游记与照片关联 | `journal_id`、`photo_id` | — |

### 预订

| 表 | 说明 | 关键列 |
| --- | --- | --- |
| `bookings` | 预订（机票/酒店/火车/门票/其他） | `type`、`title`、`start_time`、`end_time`、`order_no`、`confirm_code`、`attachment_url`、`extra`（JSON，承载类型特有字段）、`reminder_minutes`、`reminder_enabled`、`reminder_sent_at`、`creator_id` |

`extra` 的键按类型约定：机票 `flightNo`/`airports`/`cabin`，酒店 `hotelName`/`roomType`/`cancelPolicy`/`freeCancelDeadline`，
火车 `trainNo`/`stations`/`seat`，门票 `itemName`/`session`，其他 `pickup`。

> 后端派生规则：酒店传入 `extra.freeCancelDeadline` 时，自动把 `reminder_minutes`
> 派生为「开始时间 − 免费取消截止时间」的分钟数。

## 三、枚举值一览

> 以下为数据库实际定义（`pg_enum`），前端展示文案与服务端常量映射以代码为准。

| 枚举 | 取值 |
| --- | --- |
| `UserStatus` | `active` / `disabled` / `deleted` |
| `TripStatus` | `draft` / `planning` / `active` / `completed` / `archived` |
| `TripMemberRole` | `owner` / `admin` / `member` / `viewer` |
| `TripPlaceType` | `attraction` / `hotel` / `restaurant` / `transport` / `shopping` / `custom` / `parking` / `gas_station` / `service_area` / `campsite` / `photo_spot` / `repair_shop` / `charging_station` / `restroom` |
| `TripExpenseCategory` | `transport` / `hotel` / `food` / `ticket` / `shopping` / `activity` / `other` / `parking` / `maintenance` / `fuel` / `toll` / `entertainment` |
| `ExpenseSplitType` | `equal` / `ratio` / `amount` |
| `TravelDocumentType` | `passport` / `visa` / `flight` / `hotel` / `ticket` / `insurance` / `other` |
| `PackingListCategory` | `clothing` / `toiletries` / `electronics` / `medicine` / `documents` / `other` |
| `NotificationType` | `trip_upcoming` / `document_expiring` / `packing_pending` / `member_invited` / `role_changed` / `system` |
| `NotificationPriority` | `low` / `normal` / `high` |

> ⚠️ **枚举漂移提示**：`TripPlaceType` 与 `TripExpenseCategory` 的实际取值多于前端当前使用的子集
> （早期 schema 只声明了前者 6 值、后者 9 值）。前端遇到未知值时统一回落到默认样式
> （地点类型回落 `custom`，费用类别回落 `other`），新增取值时需同步前端配色映射。

## 四、通用设计约束

1. **主键**：统一为 cuid 字符串（`common/Ids` 生成），非自增整型，便于跨环境导入导出。
2. **时间**：`created_at` / `updated_at` 为标准列；序列化按 `application.yml` 的 UTC 约定输出
   （`jackson.time-zone: UTC`），前端负责转本地展示。
3. **软删**：仅 `users` 与 `trips` 有 `deleted_at`；其余子资源直接物理删除，
   需归档的场景用 `archived_at`（行程）。
4. **行级属主**：协作产出的内容表带 `uploader_id` / `author_id` / `creator_id`，
   写入时以服务端当前登录用户为准；修改与删除要求「属主本人或 owner/admin」。
5. **金额**：一律 `BigDecimal`；分摊后尾差由付款人承担，保证各份之和等于总额。
6. **排序列**：需要手工排序的场景用 `sort_order`（地点）或 `order_index`（清单条目）。
7. **外键**：均带显式外键约束；大批删除走「先查子资源再逐条删」的服务层逻辑，
   不依赖数据库级联，便于审计与权限校验。
