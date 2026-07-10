import { Injectable } from '@nestjs/common';
import { NotificationPriority, NotificationType, Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

export type CreateNotificationInput = {
  userId: string;
  type: NotificationType;
  title: string;
  content: string;
  link?: string | null;
  metadata?: Prisma.InputJsonObject;
  priority?: NotificationPriority;
  expiresAt?: Date | null;
};

@Injectable()
export class NotificationsRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findForUser(userId: string, skip: number, take: number, now: Date) {
    const where: Prisma.NotificationWhereInput = {
      userId,
      OR: [{ expiresAt: null }, { expiresAt: { gte: now } }],
    };
    const [notifications, total] = await this.prisma.$transaction([
      this.prisma.notification.findMany({
        where,
        orderBy: [{ isRead: 'asc' }, { createdAt: 'desc' }],
        skip,
        take,
      }),
      this.prisma.notification.count({ where }),
    ]);
    return { notifications, total };
  }

  countUnread(userId: string, now: Date) {
    return this.prisma.notification.count({
      where: {
        userId,
        isRead: false,
        OR: [{ expiresAt: null }, { expiresAt: { gte: now } }],
      },
    });
  }

  markRead(notificationId: string, userId: string, readAt: Date) {
    return this.prisma.notification.updateMany({
      where: { id: notificationId, userId },
      data: { isRead: true, readAt },
    });
  }

  markAllRead(userId: string, readAt: Date) {
    return this.prisma.notification.updateMany({
      where: { userId, isRead: false },
      data: { isRead: true, readAt },
    });
  }

  clearRead(userId: string) {
    return this.prisma.notification.deleteMany({ where: { userId, isRead: true } });
  }

  create(data: CreateNotificationInput) {
    return this.prisma.notification.create({ data });
  }

  createUnique(data: CreateNotificationInput, dedupeKey: string) {
    return this.prisma.$transaction(async (tx) => {
      const lockKey = `${data.userId}:${dedupeKey}`;
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${lockKey}))`;
      const existing = await tx.notification.findFirst({
        where: {
          userId: data.userId,
          type: data.type,
          metadata: { path: ['dedupeKey'], equals: dedupeKey },
        },
      });
      if (existing) return existing;

      return tx.notification.create({
        data: {
          ...data,
          metadata: { ...(data.metadata ?? {}), dedupeKey },
        },
      });
    });
  }

  findUpcomingTrips(userId: string, from: Date, to: Date) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          deletedAt: null,
          archivedAt: null,
          startDate: { gte: from, lte: to },
        },
      },
      include: { trip: true },
    });
  }

  findExpiringDocuments(userId: string, from: Date, to: Date) {
    return this.prisma.travelDocument.findMany({
      where: {
        isReminder: true,
        expiredAt: { gte: from, lte: to },
        trip: { deletedAt: null, members: { some: { userId } } },
      },
      include: { trip: true },
    });
  }

  findTripsWithPendingPacking(userId: string, from: Date, to: Date) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          deletedAt: null,
          archivedAt: null,
          startDate: { gte: from, lte: to },
        },
      },
      include: {
        trip: {
          include: {
            packingLists: {
              include: { items: { where: { isPacked: false } } },
            },
          },
        },
      },
    });
  }
}
