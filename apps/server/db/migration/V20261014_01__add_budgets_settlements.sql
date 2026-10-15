-- V20261014_01: 预算、记账与分摊（设计文档 3.4）
-- 幂等：可重复执行；不得含 ALTER TYPE ... ADD VALUE

-- 1) 类别预算表（trip_id 在表内，数据权限拦截器兜底）
CREATE TABLE IF NOT EXISTS trip_budgets (
  id         TEXT PRIMARY KEY,
  trip_id    TEXT NOT NULL,
  category   TEXT NOT NULL,
  amount     NUMERIC(12,2) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_trip_budgets_trip FOREIGN KEY (trip_id)
    REFERENCES trips (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT uq_trip_budgets UNIQUE (trip_id, category)
);
CREATE INDEX IF NOT EXISTS idx_trip_budgets_trip ON trip_budgets (trip_id);

-- 2) 结算快照表
CREATE TABLE IF NOT EXISTS trip_settlements (
  id           TEXT PRIMARY KEY,
  trip_id      TEXT NOT NULL,
  snapshot     JSONB NOT NULL,
  status       TEXT NOT NULL DEFAULT 'confirmed',
  confirmed_by TEXT NOT NULL,
  created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_trip_settlements_trip FOREIGN KEY (trip_id)
    REFERENCES trips (id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_trip_settlements_trip ON trip_settlements (trip_id, created_at);

-- 3) 记账小票图片
ALTER TABLE trip_expenses ADD COLUMN IF NOT EXISTS receipt_url TEXT;

-- 4) 行程点预估费用（预算「预估占用」来源之一）
ALTER TABLE trip_places ADD COLUMN IF NOT EXISTS estimated_cost NUMERIC(12,2);

-- 5) 报表查询索引（按行程+日期）
CREATE INDEX IF NOT EXISTS idx_trip_expenses_trip_spent ON trip_expenses (trip_id, spent_at);
