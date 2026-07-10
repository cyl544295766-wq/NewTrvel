import { Injectable, NotFoundException } from '@nestjs/common';
import {
  NotificationPriority,
  NotificationType,
  TripMemberRole,
} from '@prisma/client';
import {
  CreateNotificationInput,
  NotificationsRepository,
} from './notifications.repository';

const tripReminderDays = new Set([1, 3, 7]);
const roleLabels: Record<TripMemberRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  viewer: '观察者',
};

@Injectable()
export class NotificationsService {
  constructor(private readonly notificationsRepository: NotificationsRepository) {}

  async findAll(userId: string, pageValue?: string, pageSizeValue?: string) {
    const page = this.toPositiveInteger(pageValue, 1);
    const pageSize = Math.min(this.toPositiveInteger(pageSizeValue, 20), 100);
    const result = await this.notificationsRepository.findForUser(
      userId,
      (page - 1) * pageSize,
      pageSize,
      new Date(),
    );
    return {
      ...result,
      page,
      pageSize,
      totalPages: Math.ceil(result.total / pageSize),
    };
  }

  async getUnreadCount(userId: string) {
    return { count: await this.notificationsRepository.countUnread(userId, new Date()) };
  }

  async markRead(notificationId: string, userId: string) {
    const result = await this.notificationsRepository.markRead(notificationId, userId, new Date());
    if (result.count === 0) throw new NotFoundException('通知不存在');
    return { success: true };
  }

  async markAllRead(userId: string) {
    const result = await this.notificationsRepository.markAllRead(userId, new Date());
    return { success: true, count: result.count };
  }

  async clearRead(userId: string) {
    const result = await this.notificationsRepository.clearRead(userId);
    return { success: true, count: result.count };
  }

  create(input: CreateNotificationInput) {
    return this.notificationsRepository.create(input);
  }

  createMemberInvited(userId: string, tripId: string, tripTitle: string, role: TripMemberRole) {
    return this.create({
      userId,
      type: NotificationType.member_invited,
      title: '你已加入新的旅行',
      content: `你已被加入旅行「${tripTitle}」，角色为${roleLabels[role]}。`,
      link: `/trips/${tripId}`,
      metadata: { tripId, role },
      priority: NotificationPriority.normal,
    });
  }

  createRoleChanged(userId: string, tripId: string, tripTitle: string, role: TripMemberRole) {
    return this.create({
      userId,
      type: NotificationType.role_changed,
      title: '旅行角色已变更',
      content: `你在旅行「${tripTitle}」中的角色已变更为${roleLabels[role]}。`,
      link: `/trips/${tripId}`,
      metadata: { tripId, role },
      priority: NotificationPriority.normal,
    });
  }

  async generateForUser(userId: string) {
    const today = startOfDay(new Date());
    const inSevenDays = addDays(endOfDay(today), 7);
    const inThirtyDays = addDays(endOfDay(today), 30);
    const inThreeDays = addDays(endOfDay(today), 3);
    const [trips, documents, packingTrips] = await Promise.all([
      this.notificationsRepository.findUpcomingTrips(userId, today, inSevenDays),
      this.notificationsRepository.findExpiringDocuments(userId, today, inThirtyDays),
      this.notificationsRepository.findTripsWithPendingPacking(userId, today, inThreeDays),
    ]);
    const tasks: Promise<unknown>[] = [];

    for (const membership of trips) {
      const startDate = membership.trip.startDate!;
      const daysUntil = differenceInCalendarDays(startDate, today);
      if (!tripReminderDays.has(daysUntil)) continue;
      tasks.push(
        this.notificationsRepository.createUnique(
          {
            userId,
            type: NotificationType.trip_upcoming,
            title: '行程即将开始',
            content: `旅行「${membership.trip.title}」将在 ${daysUntil} 天后出发。`,
            link: `/trips/${membership.trip.id}`,
            metadata: { tripId: membership.trip.id, daysUntil },
            priority: daysUntil === 1 ? NotificationPriority.high : NotificationPriority.normal,
            expiresAt: addDays(endOfDay(startDate), 1),
          },
          `trip:${membership.trip.id}:${dateKey(startDate)}:${daysUntil}`,
        ),
      );
    }

    for (const document of documents) {
      const expiredAt = document.expiredAt!;
      const daysUntil = differenceInCalendarDays(expiredAt, today);
      tasks.push(
        this.notificationsRepository.createUnique(
          {
            userId,
            type: NotificationType.document_expiring,
            title: '旅行文档即将过期',
            content: `文档「${document.title}」${formatExpiry(daysUntil)}。`,
            link: `/trips/${document.tripId}/documents`,
            metadata: { tripId: document.tripId, documentId: document.id, daysUntil },
            priority: NotificationPriority.high,
            expiresAt: addDays(endOfDay(expiredAt), 1),
          },
          `document:${document.id}:${dateKey(expiredAt)}`,
        ),
      );
    }

    for (const membership of packingTrips) {
      const startDate = membership.trip.startDate!;
      const daysUntil = differenceInCalendarDays(startDate, today);
      const pendingCount = membership.trip.packingLists.reduce(
        (total, list) => total + list.items.length,
        0,
      );
      if (pendingCount === 0) continue;
      tasks.push(
        this.notificationsRepository.createUnique(
          {
            userId,
            type: NotificationType.packing_pending,
            title: '打包清单尚未完成',
            content: `旅行「${membership.trip.title}」还有 ${pendingCount} 件物品未打包。`,
            link: `/trips/${membership.trip.id}/packing-lists`,
            metadata: { tripId: membership.trip.id, pendingCount, daysUntil },
            priority: daysUntil <= 1 ? NotificationPriority.high : NotificationPriority.normal,
            expiresAt: addDays(endOfDay(startDate), 1),
          },
          `packing:${membership.trip.id}:${dateKey(startDate)}`,
        ),
      );
    }

    await Promise.all(tasks);
    return { success: true, generated: tasks.length };
  }

  private toPositiveInteger(value: string | undefined, fallback: number) {
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
  }
}

function startOfDay(value: Date) {
  const result = new Date(value);
  result.setHours(0, 0, 0, 0);
  return result;
}

function endOfDay(value: Date) {
  const result = new Date(value);
  result.setHours(23, 59, 59, 999);
  return result;
}

function addDays(value: Date, days: number) {
  const result = new Date(value);
  result.setDate(result.getDate() + days);
  return result;
}

function differenceInCalendarDays(value: Date, base: Date) {
  const left = Date.UTC(value.getFullYear(), value.getMonth(), value.getDate());
  const right = Date.UTC(base.getFullYear(), base.getMonth(), base.getDate());
  return Math.round((left - right) / 86_400_000);
}

function dateKey(value: Date) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(
    value.getDate(),
  ).padStart(2, '0')}`;
}

function formatExpiry(daysUntil: number) {
  if (daysUntil === 0) return '将在今天过期';
  return `将在 ${daysUntil} 天后过期`;
}
