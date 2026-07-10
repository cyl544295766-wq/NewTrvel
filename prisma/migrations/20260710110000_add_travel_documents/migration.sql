-- CreateEnum
CREATE TYPE "TravelDocumentType" AS ENUM ('passport', 'visa', 'flight', 'hotel', 'ticket', 'insurance', 'other');

-- CreateTable
CREATE TABLE "travel_documents" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "trip_day_id" TEXT,
    "trip_place_id" TEXT,
    "type" "TravelDocumentType" NOT NULL,
    "title" TEXT NOT NULL,
    "url" TEXT NOT NULL,
    "notes" TEXT,
    "expired_at" TIMESTAMP(3),
    "is_reminder" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "travel_documents_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "travel_documents_trip_id_idx" ON "travel_documents"("trip_id");

-- CreateIndex
CREATE INDEX "travel_documents_trip_day_id_idx" ON "travel_documents"("trip_day_id");

-- CreateIndex
CREATE INDEX "travel_documents_trip_place_id_idx" ON "travel_documents"("trip_place_id");

-- CreateIndex
CREATE INDEX "travel_documents_is_reminder_expired_at_idx" ON "travel_documents"("is_reminder", "expired_at");

-- AddForeignKey
ALTER TABLE "travel_documents" ADD CONSTRAINT "travel_documents_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "travel_documents" ADD CONSTRAINT "travel_documents_trip_day_id_fkey" FOREIGN KEY ("trip_day_id") REFERENCES "trip_days"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "travel_documents" ADD CONSTRAINT "travel_documents_trip_place_id_fkey" FOREIGN KEY ("trip_place_id") REFERENCES "trip_places"("id") ON DELETE SET NULL ON UPDATE CASCADE;
