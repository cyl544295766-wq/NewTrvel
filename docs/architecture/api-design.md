# 接口设计

## 一、基础约定

| 项 | 约定 |
| --- | --- |
| 前缀 | 所有接口以 `/api` 开头，与前端静态资源同域，因此**不需要跨域凭据** |
| 内容类型 | 请求与响应均为 `application/json;charset=UTF-8` |
| 认证 | `Authorization: Bearer <token>`（优先），无则回落 HttpOnly Cookie |
| 时间格式 | 服务端按 UTC 序列化（`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`），前端转本地展示 |
| 金额 | 字符串或数字形式的 Decimal，前端按 `¥` + 两位小数展示 |

## 二、响应约定

**成功**：直接返回业务对象或数组，不用 `{ code, data }` 包装。

```jsonc
// 集合类接口会带一层具名键，便于后续扩展同页其他数据
{ "trips": [ ... ] }
{ "days": [ { "dayIndex": 1, "places": [ ... ], "bookings": [ ... ] } ] }

// 单资源直接返回对象
{ "id": "...", "title": "赤峰之旅", "status": "planning", ... }

// 聚合类接口返回多个键
{ "photos": [ ... ], "total": 12 }
```

**失败**：`GlobalExceptionHandler` 统一转为 JSON，`message` 为可直接展示的中文短句。

```jsonc
{ "message": "仅上传者本人或行程管理员可执行该操作" }
```

前端统一用 `humanError(err)` 转换后展示，禁止直接把状态码抛给用户。

## 三、状态码

| 状态码 | 含义 | 前端处理 |
| --- | --- | --- |
| 200 / 201 | 成功 | — |
| 400 | 参数校验失败 / 业务规则拒绝 | 展示 `message` |
| 401 | 未登录或令牌失效 | 清理本地登录态 → 跳登录页（带回跳地址） |
| 403 | 已登录但无权限（非行程成员 / 角色不足 / 非属主） | 展示 `message` |
| 404 | 资源不存在或无权查看 | 展示引导或返回上级 |
| 500 | 服务端异常 | 展示通用错误 + 重试入口 |
| 503 | 依赖未配置（如地图 Web 服务 Key 缺失） | 功能降级为手动输入 |

## 四、分页约定

仅通知等长列表接口分页，参数与响应统一：

```
GET /api/notifications?page=1&pageSize=20
```

```jsonc
{ "notifications": [ ... ], "total": 37, "page": 1, "pageSize": 20, "totalPages": 2 }
```

> 注意：查询时会**过滤 `expires_at` 已过期的记录**，因此 `total` 可能小于表内实际行数。

## 五、文件上传

本项目**不使用 multipart 上传**（`spring.servlet.multipart.enabled: false`）。
前端读取文件后转成 **data URL**，作为普通 JSON 字段提交：

| 资源 | 字段 | 限制 |
| --- | --- | --- |
| 照片 | `url` | JPG / PNG / WebP，≤ 5 MB，每行程上限 500 张 |
| 证件文档 | `url` | PDF / JPG / PNG / WebP，≤ 10 MB |
| 预订凭证附件 | `attachmentUrl` | 宽松字符串（不做 data URL 校验） |

服务端由 `common/PhotoUtil` 校验前缀、MIME 与解码后字节数。

## 六、接口分组

### 全局

| 路径 | 说明 |
| --- | --- |
| `/api/auth` | 注册、登录、登出、`me`、注销账户 |
| `/api/health` | 服务与数据库健康检查 |
| `/api/dashboard` | 首页聚合（行程摘要、待办提醒） |
| `/api/stats` | 全站统计：`overview` / `yearly` / `monthly` / `expense-categories` |
| `/api/notifications` | 通知列表、未读数、标记已读、全部已读、清除已读 |
| `/api/places` | 地点搜索联想（依赖高德 Web 服务 Key，未配置返回 503） |
| `/api/ai/conversations` | AI 助手会话与消息 |

### 行程主资源

| 路径 | 说明 |
| --- | --- |
| `/api/trips` | 行程列表 / 新建 |
| `/api/trips/{tripId}` | 行程详情 / 更新 / 删除 |
| `/api/trips/{tripId}/favorite` | 收藏切换 |
| `/api/trips/{tripId}/archive` | 归档 |
| `/api/trips/{tripId}/duplicate` | 复制行程 |
| `/api/trips/{tripId}/route` | 地图用：按天聚合的带坐标地点 |

### 行程子资源

统一挂在 `/api/trips/{tripId}/` 之下，**每个请求都会先校验调用者是该行程成员**。

| 子路径 | 说明 |
| --- | --- |
| `members` | 成员列表、邀请、角色变更、移除 |
| `days` | 行程天：列表、按日期批量生成、更新标题 |
| `days/{dayId}/places` | 地点增删改、`reorder` 排序 |
| `expenses` | 费用：列表、增删改、`summary`、`settlement`、`report`、`budget`、`settlement/confirm` |
| `bookings` | 预订：列表、增删改、`vouchers`（凭证聚合） |
| `packing-lists` | 清单：列表、增删改、`duplicate`、条目增删改 |
| `documents` | 证件文档：列表、增删改 |
| `photos` | 照片：列表、增删改、设为封面 |
| `journals` | 游记：列表、增删改、`photos/reorder` |
| `budget` | 行程预算：读取、按类别更新 |
| `weather` | 行程天气 |
| `audit-logs` | 该行程的敏感操作审计记录 |
| `export/pdf` | 导出行程 PDF（注意是 `/export/pdf`，不是 `/pdf`） |

## 七、权限矩阵

| 读取 | 写入（增删改） | 危险操作 |
| --- | --- | --- |
| 全部四种角色 | `owner` / `admin` / `member` | 属主本人或 `owner` / `admin` |

- `viewer` 为**只读**角色，所有写接口返回 403。
- 带行级属主的资源（照片、文档、游记、清单）在写入时以服务端当前用户写入属主，
  前端传入的属主字段一律忽略。
- 角色校验用 `@RequireTripRole({...})` 注解 + `TripRoleAspect` 切面统一实现。
