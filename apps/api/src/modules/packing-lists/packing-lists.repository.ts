import { Injectable } from '@nestjs/common';
import { PackingItem, PackingList, Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

type PackingListWithItems = PackingList & { items: PackingItem[] };

@Injectable()
export class PackingListsRepository {
  constructor(private readonly prisma: PrismaService) {}

  findListsByTrip(tripId: string) {
    return this.prisma.packingList.findMany({
      where: { tripId },
      include: { items: { orderBy: { orderIndex: 'asc' } } },
      orderBy: { createdAt: 'desc' },
    });
  }

  findListById(listId: string) {
    return this.prisma.packingList.findUnique({
      where: { id: listId },
      include: { items: { orderBy: { orderIndex: 'asc' } } },
    });
  }

  findItemById(itemId: string) {
    return this.prisma.packingItem.findUnique({ where: { id: itemId } });
  }

  createList(data: Prisma.PackingListCreateInput) {
    return this.prisma.packingList.create({
      data,
      include: { items: { orderBy: { orderIndex: 'asc' } } },
    });
  }

  updateList(listId: string, data: Prisma.PackingListUpdateInput) {
    return this.prisma.packingList.update({
      where: { id: listId },
      data,
      include: { items: { orderBy: { orderIndex: 'asc' } } },
    });
  }

  deleteList(listId: string) {
    return this.prisma.$transaction(async (tx) => {
      await tx.packingItem.deleteMany({ where: { packingListId: listId } });
      return tx.packingList.delete({ where: { id: listId } });
    });
  }

  createItem(listId: string, data: Prisma.PackingItemCreateWithoutPackingListInput) {
    return this.prisma.packingItem.create({
      data: { ...data, packingList: { connect: { id: listId } } },
    });
  }

  updateItem(itemId: string, data: Prisma.PackingItemUpdateInput) {
    return this.prisma.packingItem.update({ where: { id: itemId }, data });
  }

  deleteItem(itemId: string) {
    return this.prisma.packingItem.delete({ where: { id: itemId } });
  }

  duplicateList(tripId: string, source: PackingListWithItems, name: string) {
    return this.prisma.packingList.create({
      data: {
        trip: { connect: { id: tripId } },
        name,
        category: source.category,
        items: {
          create: source.items.map((item) => ({
            name: item.name,
            quantity: item.quantity,
            isPacked: false,
            notes: item.notes,
            orderIndex: item.orderIndex,
          })),
        },
      },
      include: { items: { orderBy: { orderIndex: 'asc' } } },
    });
  }

  countPendingItemsForTrips(tripIds: string[]) {
    return this.prisma.packingItem.count({
      where: {
        isPacked: false,
        packingList: { tripId: { in: tripIds } },
      },
    });
  }
}
