-- 用户角色：user（普通用户）/ super_admin（超级管理员，拥有管理后台全部权限）。
-- 存量默认 user；种子管理员（admin）提升为 super_admin。
ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "role" TEXT NOT NULL DEFAULT 'user';
UPDATE "users" SET "role" = 'super_admin' WHERE "username" = 'admin' AND "role" = 'user';
