DO $$ BEGIN
  CREATE TYPE "NotificationType" AS ENUM (
    'trip_upcoming',
    'document_expiring',
    'packing_pending',
    'member_invited',
    'role_changed',
    'system'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  CREATE TYPE "NotificationPriority" AS ENUM ('low', 'normal', 'high');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS "notifications" (
  "id" TEXT NOT NULL,
  "user_id" TEXT NOT NULL,
  "type" "NotificationType" NOT NULL,
  "title" TEXT NOT NULL,
  "content" TEXT NOT NULL,
  "link" TEXT,
  "metadata" JSONB,
  "is_read" BOOLEAN NOT NULL DEFAULT false,
  "priority" "NotificationPriority" NOT NULL DEFAULT 'normal',
  "read_at" TIMESTAMP(3),
  "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "expires_at" TIMESTAMP(3),

  CONSTRAINT "notifications_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "notifications_user_id_idx" ON "notifications"("user_id");

CREATE INDEX IF NOT EXISTS "notifications_is_read_idx" ON "notifications"("is_read");

CREATE INDEX IF NOT EXISTS "notifications_created_at_idx" ON "notifications"("created_at");

DO $$ BEGIN
  ALTER TABLE "notifications"
    ADD CONSTRAINT "notifications_user_id_fkey"
    FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
