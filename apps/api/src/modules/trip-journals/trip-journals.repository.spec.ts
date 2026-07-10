import { TripJournalsRepository } from './trip-journals.repository';

describe('TripJournalsRepository', () => {
  it('rebuilds photo links in the requested order during update', async () => {
    const tx = {
      tripJournal: {
        update: jest.fn().mockResolvedValue({ id: 'journal-1' }),
        findUniqueOrThrow: jest.fn().mockResolvedValue({ id: 'journal-1', photos: [] }),
      },
      tripJournalPhoto: {
        deleteMany: jest.fn().mockResolvedValue({ count: 2 }),
        createMany: jest.fn().mockResolvedValue({ count: 2 }),
      },
    };
    const repository = new TripJournalsRepository({
      $transaction: jest.fn((callback) => callback(tx)),
    } as never);

    await repository.updateJournal('journal-1', { title: '更新标题' }, ['photo-2', 'photo-1']);

    expect(tx.tripJournalPhoto.deleteMany).toHaveBeenCalledWith({
      where: { journalId: 'journal-1' },
    });
    expect(tx.tripJournalPhoto.createMany).toHaveBeenCalledWith({
      data: [
        { journalId: 'journal-1', photoId: 'photo-2', orderIndex: 0 },
        { journalId: 'journal-1', photoId: 'photo-1', orderIndex: 1 },
      ],
    });
  });

  it('queries the three most recent published journals', async () => {
    const findMany = jest.fn().mockResolvedValue([]);
    const repository = new TripJournalsRepository({ tripJournal: { findMany } } as never);

    await repository.findRecentPublishedForTrips(['trip-1'], 3);

    expect(findMany).toHaveBeenCalledWith({
      where: { tripId: { in: ['trip-1'] }, isDraft: false },
      include: { trip: true },
      orderBy: { createdAt: 'desc' },
      take: 3,
    });
  });
});
