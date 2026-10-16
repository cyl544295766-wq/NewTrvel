-- 好友聊天：1:1 私信（sender → receiver），read_at 空表示未读
CREATE TABLE IF NOT EXISTS "friend_messages" (
    "id" TEXT NOT NULL,
    "sender_id" TEXT NOT NULL,
    "receiver_id" TEXT NOT NULL,
    "content" TEXT NOT NULL,
    "read_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "friend_messages_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "friend_messages_receiver_idx" ON "friend_messages" ("receiver_id", "created_at");
CREATE INDEX IF NOT EXISTS "friend_messages_sender_idx" ON "friend_messages" ("sender_id", "created_at");

-- 攻略共同编辑互斥锁：同一时间有且只有一人持有（journal_id 主键天然互斥），
-- expires_at 过期后可被抢占（编辑器崩溃等异常场景的兜底）
CREATE TABLE IF NOT EXISTS "journal_edit_locks" (
    "journal_id" TEXT NOT NULL,
    "holder_id" TEXT NOT NULL,
    "holder_name" TEXT NOT NULL,
    "acquired_at" TIMESTAMP(3) NOT NULL,
    "expires_at" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "journal_edit_locks_pkey" PRIMARY KEY ("journal_id")
);
