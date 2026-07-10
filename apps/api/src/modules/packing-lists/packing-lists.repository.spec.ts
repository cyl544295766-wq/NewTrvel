import { PackingListCategory } from '@prisma/client';
import { PackingListsRepository } from './packing-lists.repository';

describe('PackingListsRepository', () => {
  it('resets packed state when duplicating a list', async () => {
    const create = jest.fn().mockResolvedValue({ id: 'list-2', items: [] });
    const repository = new PackingListsRepository({ packingList: { create } } as never);

    await repository.duplicateList(
      'trip-1',
      {
        id: 'list-1',
        tripId: 'trip-1',
        name: '电子设备',
        category: PackingListCategory.electronics,
        createdAt: new Date(),
        updatedAt: new Date(),
        items: [
          {
            id: 'item-1',
            packingListId: 'list-1',
            name: '充电器',
            quantity: 1,
            isPacked: true,
            notes: null,
            orderIndex: 0,
          },
        ],
      },
      '电子设备 副本',
    );

    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          name: '电子设备 副本',
          items: {
            create: [expect.objectContaining({ name: '充电器', quantity: 1, isPacked: false })],
          },
        }),
      }),
    );
  });

  it('counts unpacked items for the selected trips', async () => {
    const count = jest.fn().mockResolvedValue(4);
    const repository = new PackingListsRepository({ packingItem: { count } } as never);

    await expect(repository.countPendingItemsForTrips(['trip-1'])).resolves.toBe(4);
    expect(count).toHaveBeenCalledWith({
      where: { isPacked: false, packingList: { tripId: { in: ['trip-1'] } } },
    });
  });
});
