-- 好友系统：单向「添加」记录，互为好友 = 双向都存在记录。
-- 账本（行程）邀请成员时要求双方互为好友（TripMemberService.invite 校验）。
CREATE TABLE IF NOT EXISTS "user_friends" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "friend_id" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "user_friends_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "user_friends_pair_key" UNIQUE ("user_id", "friend_id")
);
CREATE INDEX IF NOT EXISTS "user_friends_friend_idx" ON "user_friends" ("friend_id");
