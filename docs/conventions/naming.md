# 命名约定

## 数据库

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 表 | 小写 `snake_case`、复数 | `trips`、`trip_expense_shares` |
| 列 | 小写 `snake_case` | `start_date`、`is_favorite`、`uploader_id` |
| 主键 | 统一 `id`（cuid 字符串） | — |
| 外键 | `<单数实体>_id` | `trip_id`、`trip_day_id`、`travel_document_id` |
| 时间列 | `_at` 后缀 | `created_at`、`updated_at`、`deleted_at`、`expired_at` |
| 布尔列 | `is_` 前缀 | `is_cover`、`is_draft`、`is_packed`、`is_completed` |
| 排序列 | `sort_order` / `order_index` | — |

## 服务端（Java）

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 包 | 全小写、单数 | `controller`、`service`、`mapper`、`entity` |
| 分层类 | 实体名 + 角色后缀 | `TripExpenseController` / `TripExpenseService` / `TripExpenseMapper` |
| 实体类 | 与表对应的单数 `PascalCase` | `TripExpense`、`TravelDocument` |
| 方法 | `camelCase`、动词开头 | `findAll`、`findOne`、`requireTripMember`、`clearOtherCovers` |
| 常量 | 全大写 `SNAKE_CASE` | `EDITABLE_ROLES`、`CATEGORY_LABELS` |
| 入参 DTO | `XxxRequest` | `LoginRequest` |
| 出参 | `Map<String, Object>` 手工组装（便于前端按需取字段） | — |

## 接口（REST）

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 资源路径 | 复数名词，多词用 `kebab-case` | `/api/trips`、`/api/trips/{id}/packing-lists` |
| 子资源 | 挂在父资源下，体现归属 | `/api/trips/{tripId}/expenses` |
| 单资源操作 | `PATCH` 部分更新，`PUT` 整体替换 | `/api/trips/{id}/favorite` |
| 查询参数 | `camelCase` | `?page=1&pageSize=20&keyword=xx&year=2026` |
| 分页响应 | `{ items, total, page, pageSize, totalPages }` | 见 `docs/architecture/api-design.md` |

## 前端

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 页面目录 | `kebab-case`，与路由同名 | `trip-detail/`、`journal-detail/` |
| 文件名 | 全小写 | `index.html`、`theme.css`、`api.js` |
| CSS 类 | `kebab-case`、语义化，避免按外观命名 | `.day-block`、`.pass-stub`、`.mk-dot` |
| CSS 变量 | `--kebab-case` | `--brand`、`--stamp`、`--r-card`、`--slab` |
| JS 函数 | `camelCase`、动词开头 | `renderDays`、`loadAll`、`apiFetch`、`humanError` |
| DOM id | `camelCase` | `#dayList`、`#tcTitle`、`#mapStage` |
| localStorage 键 | `travelos.` 前缀 | `travelos.token`、`travelos.user` |

## Git

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 分支 | `feature/*`、`fix/*` | `feature/trip-budget` |
| 提交信息 | `type(scope): 中文描述` | `feat(booking): 酒店预订支持免费取消截止日提醒` |
| 常用 type | `feat` / `fix` / `chore` / `ci` / `docs` / `refactor` | — |
