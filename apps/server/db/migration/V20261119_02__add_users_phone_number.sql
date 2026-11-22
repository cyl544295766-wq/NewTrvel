-- V20261119_02: 记录华为账号的手机号
-- 手机号来自 Account Kit 的 phone scope（用户授权后由 GOpen.User.getInfo 返回），
-- 仅用于账号资料展示与后续绑定，不参与登录匹配（登录锚点仍是 huawei_open_id）。
-- 幂等：ADD COLUMN IF NOT EXISTS。
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number text;
