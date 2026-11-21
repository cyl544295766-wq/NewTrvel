-- V20261119_01: 文件对象级授权
-- 1) 最小文件归属元数据：name = 32位hex.ext，owner = 上传时的登录用户。
-- 2) 为 /files/** 授权查询补充 URL 查找索引，避免每次请求全表扫描。
-- 3) 存量数据从可靠的属主列回填；无法确定归属的文件保持无元数据（默认拒绝非授权访问）。

CREATE TABLE IF NOT EXISTS "file_metadata" (
    "name" TEXT NOT NULL,
    "owner_user_id" TEXT NOT NULL REFERENCES "users"("id"),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "file_metadata_pkey" PRIMARY KEY ("name")
);
CREATE INDEX IF NOT EXISTS "file_metadata_owner_idx" ON "file_metadata" ("owner_user_id");

-- 头像优先回填：一个用户只能拥有自己的头像文件。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("avatar_url" from 8), "id"
FROM "users"
WHERE "avatar_url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
ON CONFLICT ("name") DO NOTHING;

-- 行程照片：上传者即文件创建者。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("url" from 8), "uploader_id"
FROM "photos"
WHERE "url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
  AND "uploader_id" IS NOT NULL
ON CONFLICT ("name") DO NOTHING;

-- 行程证件/票据/文档：上传者即文件创建者。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("url" from 8), "uploader_id"
FROM "travel_documents"
WHERE "url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
  AND "uploader_id" IS NOT NULL
ON CONFLICT ("name") DO NOTHING;

-- 报销凭证：历史表无 creator 列，使用付款人作为可回填的行程成员属主。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("receipt_url" from 8), "payer_user_id"
FROM "trip_expenses"
WHERE "receipt_url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
  AND "payer_user_id" IS NOT NULL
ON CONFLICT ("name") DO NOTHING;

-- 预订附件：创建者。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("attachment_url" from 8), "creator_id"
FROM "bookings"
WHERE "attachment_url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
  AND "creator_id" IS NOT NULL
ON CONFLICT ("name") DO NOTHING;

-- 行程封面：行程所有者。
INSERT INTO "file_metadata" ("name", "owner_user_id")
SELECT substring("cover_image_url" from 8), "owner_id"
FROM "trips"
WHERE "cover_image_url" ~ '^/files/[0-9a-f]{32}[.](jpg|jpeg|png|webp|pdf)$'
  AND "owner_id" IS NOT NULL
ON CONFLICT ("name") DO NOTHING;

CREATE INDEX IF NOT EXISTS "idx_photos_url_files"
  ON "photos" ("url") WHERE "url" LIKE '/files/%';
CREATE INDEX IF NOT EXISTS "idx_travel_documents_url_files"
  ON "travel_documents" ("url") WHERE "url" LIKE '/files/%';
CREATE INDEX IF NOT EXISTS "idx_trip_expenses_receipt_url_files"
  ON "trip_expenses" ("receipt_url") WHERE "receipt_url" LIKE '/files/%';
CREATE INDEX IF NOT EXISTS "idx_bookings_attachment_url_files"
  ON "bookings" ("attachment_url") WHERE "attachment_url" LIKE '/files/%';
CREATE INDEX IF NOT EXISTS "idx_users_avatar_url_files"
  ON "users" ("avatar_url") WHERE "avatar_url" LIKE '/files/%';
CREATE INDEX IF NOT EXISTS "idx_trips_cover_image_url_files"
  ON "trips" ("cover_image_url") WHERE "cover_image_url" LIKE '/files/%';
