-- AlterEnum
ALTER TYPE "TripExpenseCategory" ADD VALUE IF NOT EXISTS 'parking';

ALTER TYPE "TripExpenseCategory" ADD VALUE IF NOT EXISTS 'maintenance';

-- AlterTable
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "budget" DECIMAL(65,30);
