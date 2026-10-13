-- V20261013_02: 数据隔离加固——为四张业务表补行级属主列
-- 幂等：全部使用 IF NOT EXISTS / 仅回填 NULL 行，可在部署时重复重放。
-- 注意：本脚本不含 ALTER TYPE ... ADD VALUE（该语句不能包在事务里，见 V20261013_01）。

-- 1) 新增属主列
ALTER TABLE photos            ADD COLUMN IF NOT EXISTS uploader_id TEXT REFERENCES users(id);
ALTER TABLE travel_documents  ADD COLUMN IF NOT EXISTS uploader_id TEXT REFERENCES users(id);
ALTER TABLE trip_journals     ADD COLUMN IF NOT EXISTS author_id   TEXT REFERENCES users(id);
ALTER TABLE packing_lists     ADD COLUMN IF NOT EXISTS creator_id  TEXT REFERENCES users(id);

-- 2) 回填存量数据：属主 = 该行程的 owner（trip_members 中 role='owner' 的用户）
--    仅更新 NULL 行，重复执行不产生副作用；找不到 owner 的行保持 NULL，
--    由代码按「行程成员可改」兼容处理。
UPDATE photos p
SET    uploader_id = tm.user_id
FROM   trip_members tm
WHERE  p.trip_id = tm.trip_id
AND    tm.role = 'owner'
AND    p.uploader_id IS NULL;

UPDATE travel_documents d
SET    uploader_id = tm.user_id
FROM   trip_members tm
WHERE  d.trip_id = tm.trip_id
AND    tm.role = 'owner'
AND    d.uploader_id IS NULL;

UPDATE trip_journals j
SET    author_id = tm.user_id
FROM   trip_members tm
WHERE  j.trip_id = tm.trip_id
AND    tm.role = 'owner'
AND    j.author_id IS NULL;

UPDATE packing_lists pl
SET    creator_id = tm.user_id
FROM   trip_members tm
WHERE  pl.trip_id = tm.trip_id
AND    tm.role = 'owner'
AND    pl.creator_id IS NULL;

-- 3) 属主列索引
CREATE INDEX IF NOT EXISTS idx_photos_uploader_id ON photos (uploader_id);
CREATE INDEX IF NOT EXISTS idx_travel_documents_uploader_id ON travel_documents (uploader_id);
CREATE INDEX IF NOT EXISTS idx_trip_journals_author_id ON trip_journals (author_id);
CREATE INDEX IF NOT EXISTS idx_packing_lists_creator_id ON packing_lists (creator_id);
