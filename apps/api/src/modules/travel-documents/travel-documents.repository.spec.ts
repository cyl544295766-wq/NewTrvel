import { TravelDocumentsRepository } from './travel-documents.repository';

describe('TravelDocumentsRepository', () => {
  it('queries reminder documents within the expiration window', async () => {
    const findMany = jest.fn().mockResolvedValue([]);
    const repository = new TravelDocumentsRepository({ travelDocument: { findMany } } as never);
    const from = new Date('2026-07-10T00:00:00.000Z');
    const to = new Date('2026-08-09T23:59:59.999Z');

    await repository.findUpcomingDocumentsForTrips(['trip-1', 'trip-2'], from, to);

    expect(findMany).toHaveBeenCalledWith({
      where: {
        tripId: { in: ['trip-1', 'trip-2'] },
        isReminder: true,
        expiredAt: { gte: from, lte: to },
      },
      include: { trip: true },
      orderBy: { expiredAt: 'asc' },
    });
  });
});
