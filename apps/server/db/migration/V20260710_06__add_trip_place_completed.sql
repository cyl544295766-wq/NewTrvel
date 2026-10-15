-- AlterTable
ALTER TABLE "trip_places" ADD COLUMN IF NOT EXISTS "is_completed" BOOLEAN NOT NULL DEFAULT false;
