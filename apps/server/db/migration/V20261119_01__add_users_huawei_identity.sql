-- V20261119_01: 华为账号（Account Kit）登录
-- 按 openID 关联本地用户（同一用户在同一个应用内 openID 恒定）；
-- unionID 记录同开发者多应用间的统一身份，仅用于后续跨应用打通，不参与登录匹配。
-- 幂等：ADD COLUMN IF NOT EXISTS / 部分唯一索引（仅约束非空行，历史密码用户不受影响）
ALTER TABLE users ADD COLUMN IF NOT EXISTS huawei_open_id text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS huawei_union_id text;

CREATE UNIQUE INDEX IF NOT EXISTS users_huawei_open_id_key
  ON users (huawei_open_id)
  WHERE huawei_open_id IS NOT NULL;
