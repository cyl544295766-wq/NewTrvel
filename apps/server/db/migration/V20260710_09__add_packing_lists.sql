-- CreateEnum
CREATE TYPE "PackingListCategory" AS ENUM ('clothing', 'toiletries', 'electronics', 'medicine', 'documents', 'other');

-- CreateTable
CREATE TABLE "packing_lists" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "category" "PackingListCategory" NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "packing_lists_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "packing_items" (
    "id" TEXT NOT NULL,
    "packing_list_id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL DEFAULT 1,
    "is_packed" BOOLEAN NOT NULL DEFAULT false,
    "notes" TEXT,
    "order_index" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "packing_items_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "packing_lists_trip_id_idx" ON "packing_lists"("trip_id");

-- CreateIndex
CREATE INDEX "packing_items_packing_list_id_idx" ON "packing_items"("packing_list_id");

-- AddForeignKey
ALTER TABLE "packing_lists" ADD CONSTRAINT "packing_lists_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "packing_items" ADD CONSTRAINT "packing_items_packing_list_id_fkey" FOREIGN KEY ("packing_list_id") REFERENCES "packing_lists"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
