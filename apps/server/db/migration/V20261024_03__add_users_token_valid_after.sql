-- V20261024_03: JWT 可吊销支持（P2-1）
-- token_valid_after 非空时，签发时间早于该值的 JWT 一律拒绝：
-- 改密/注销/禁用账户时刷新该值，实现"其他设备立即下线"
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_valid_after TIMESTAMP;
