-- CreateTable
CREATE TABLE "trip_journals" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "trip_day_id" TEXT,
    "trip_place_id" TEXT,
    "title" TEXT NOT NULL,
    "content" TEXT NOT NULL,
    "is_draft" BOOLEAN NOT NULL DEFAULT true,
    "mood" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "trip_journals_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "trip_journal_photos" (
    "journal_id" TEXT NOT NULL,
    "photo_id" TEXT NOT NULL,
    "order_index" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "trip_journal_photos_pkey" PRIMARY KEY ("journal_id", "photo_id")
);

-- CreateIndex
CREATE INDEX "trip_journals_trip_id_idx" ON "trip_journals"("trip_id");

-- CreateIndex
CREATE INDEX "trip_journals_trip_day_id_idx" ON "trip_journals"("trip_day_id");

-- CreateIndex
CREATE INDEX "trip_journals_trip_place_id_idx" ON "trip_journals"("trip_place_id");

-- CreateIndex
CREATE INDEX "trip_journals_is_draft_created_at_idx" ON "trip_journals"("is_draft", "created_at");

-- CreateIndex
CREATE INDEX "trip_journal_photos_journal_id_idx" ON "trip_journal_photos"("journal_id");

-- CreateIndex
CREATE INDEX "trip_journal_photos_photo_id_idx" ON "trip_journal_photos"("photo_id");

-- AddForeignKey
ALTER TABLE "trip_journals" ADD CONSTRAINT "trip_journals_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_journals" ADD CONSTRAINT "trip_journals_trip_day_id_fkey" FOREIGN KEY ("trip_day_id") REFERENCES "trip_days"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_journals" ADD CONSTRAINT "trip_journals_trip_place_id_fkey" FOREIGN KEY ("trip_place_id") REFERENCES "trip_places"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_journal_photos" ADD CONSTRAINT "trip_journal_photos_journal_id_fkey" FOREIGN KEY ("journal_id") REFERENCES "trip_journals"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_journal_photos" ADD CONSTRAINT "trip_journal_photos_photo_id_fkey" FOREIGN KEY ("photo_id") REFERENCES "photos"("id") ON DELETE CASCADE ON UPDATE CASCADE;
