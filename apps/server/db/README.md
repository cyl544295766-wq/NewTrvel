# 数据库迁移（db/migration）

本项目**不使用任何迁移框架**，迁移脚本由部署流水线按文件名顺序直接执行到 PostgreSQL。

## 执行方式

本地 / 服务器统一通过 `psql` 顺序执行：

```bash
for f in $(ls db/migration/*.sql | sort); do
  echo "applying: $f"
  docker exec -i travel-os-postgres psql -U travel_os -d travel_os -v ON_ERROR_STOP=1 -q -f - < "$f"
done
```

CI 中这一步见 `.github/workflows/deploy.yml` 的「应用数据库迁移」任务。

## 命名约定

```
V<yyyyMMdd>_<NN>__<snake_case_description>.sql
```

- `yyyyMMdd` 为编写日期，`NN` 为当日序号（两位，保证字典序稳定）
- 只增不改：**已执行过的脚本禁止再修改**，需要变更就新增一个脚本
- 脚本必须**幂等**（`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` /
  `DO $$ ... EXCEPTION WHEN duplicate_object ...` 等），因为流水线每次部署都会全量重放

## 脚本沿革

`V20260709_*` ~ `V20260711_*` 这 14 个脚本原为早期 Prisma ORM 时代生成的建表迁移，
早期版本改用 Spring 后曾保留在 `prisma/migrations/` 下，现统一归档到此处，
使服务端模块自包含「从零建库 → 当前 schema」的完整能力。

这些脚本已统一改造为**幂等写法**（`CREATE TABLE / INDEX` 加 `IF NOT EXISTS`；
`CREATE TYPE` 与 `ADD CONSTRAINT` 用 `DO $$ ... EXCEPTION WHEN duplicate_object ... $$` 包裹），
因此可以直接被部署流水线重放，无需额外的执行记录表。

`V20261013_*` 起为 Spring 时代的增量迁移（用户软删、行级属主、审计日志、预订、
预算与结算、AI 会话表等）。
