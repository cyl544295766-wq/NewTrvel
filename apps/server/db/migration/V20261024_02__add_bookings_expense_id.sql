-- V20261024_02: 预订→费用一键核销（P1-1）
-- bookings 记录核销目标费用；已核销的预订退出预算"预估占用"，避免与 expenses 双算
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS expense_id TEXT;
