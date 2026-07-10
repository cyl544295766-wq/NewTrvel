import { NotFoundException } from '@nestjs/common';
import { NotificationType } from '@prisma/client';
import { NotificationsRepository } from './notifications.repository';
import { NotificationsService } from './notifications.service';

describe('NotificationsService', () => {
  const repository = {
    findForUser: jest.fn(),
    countUnread: jest.fn(),
    markRead: jest.fn(),
    markAllRead: jest.fn(),
    clearRead: jest.fn(),
    create: jest.fn(),
    createUnique: jest.fn(),
    findUpcomingTrips: jest.fn(),
    findExpiringDocuments: jest.fn(),
    findTripsWithPendingPacking: jest.fn(),
  };
  const service = new NotificationsService(repository as unknown as NotificationsRepository);

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers().setSystemTime(new Date('2026-07-11T08:00:00+08:00'));
    repository.createUnique.mockResolvedValue({ id: 'notification-1' });
    repository.findUpcomingTrips.mockResolvedValue([]);
    repository.findExpiringDocuments.mockResolvedValue([]);
    repository.findTripsWithPendingPacking.mockResolvedValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('generates trip, document and packing notifications', async () => {
    repository.findUpcomingTrips.mockResolvedValue([
      {
        trip: {
          id: 'trip-1',
          title: '上海旅行',
          startDate: new Date('2026-07-18T00:00:00+08:00'),
        },
      },
    ]);
    repository.findExpiringDocuments.mockResolvedValue([
      {
        id: 'document-1',
        tripId: 'trip-1',
        title: '旅行保险',
        expiredAt: new Date('2026-07-21T00:00:00+08:00'),
        trip: { title: '上海旅行' },
      },
    ]);
    repository.findTripsWithPendingPacking.mockResolvedValue([
      {
        trip: {
          id: 'trip-2',
          title: '杭州旅行',
          startDate: new Date('2026-07-13T00:00:00+08:00'),
          packingLists: [{ items: [{ id: 'item-1' }, { id: 'item-2' }] }],
        },
      },
    ]);

    const result = await service.generateForUser('user-1');

    expect(result.generated).toBe(3);
    expect(repository.createUnique).toHaveBeenCalledTimes(3);
    expect(repository.createUnique).toHaveBeenCalledWith(
      expect.objectContaining({
        type: NotificationType.trip_upcoming,
        content: expect.stringContaining('7 天后出发'),
      }),
      expect.stringContaining('trip:trip-1'),
    );
    expect(repository.createUnique).toHaveBeenCalledWith(
      expect.objectContaining({
        type: NotificationType.document_expiring,
        priority: 'high',
      }),
      expect.stringContaining('document:document-1'),
    );
    expect(repository.createUnique).toHaveBeenCalledWith(
      expect.objectContaining({
        type: NotificationType.packing_pending,
        content: expect.stringContaining('2 件物品未打包'),
      }),
      expect.stringContaining('packing:trip-2'),
    );
  });

  it('marks the current user notification as read', async () => {
    repository.markRead.mockResolvedValue({ count: 1 });

    await expect(service.markRead('notification-1', 'user-1')).resolves.toEqual({ success: true });
    expect(repository.markRead).toHaveBeenCalledWith(
      'notification-1',
      'user-1',
      expect.any(Date),
    );
  });

  it('does not allow access to another user notification', async () => {
    repository.markRead.mockResolvedValue({ count: 0 });

    await expect(service.markRead('notification-2', 'user-1')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  it('clears only read notifications for the current user', async () => {
    repository.clearRead.mockResolvedValue({ count: 4 });

    await expect(service.clearRead('user-1')).resolves.toEqual({ success: true, count: 4 });
    expect(repository.clearRead).toHaveBeenCalledWith('user-1');
  });
});
