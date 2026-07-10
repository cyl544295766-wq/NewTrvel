import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { PackingItem, PackingList, PackingListCategory } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { PackingListsRepository } from './packing-lists.repository';
import { PackingListsService } from './packing-lists.service';

const tripId = 'trip-1';
const userId = 'user-1';
const listId = 'list-1';
const itemId = 'item-1';
const now = new Date('2026-07-10T08:00:00.000Z');

const createItem = (overrides: Partial<PackingItem> = {}): PackingItem => ({
  id: itemId,
  packingListId: listId,
  name: '充电器',
  quantity: 1,
  isPacked: false,
  notes: null,
  orderIndex: 0,
  ...overrides,
});

const createList = (
  overrides: Partial<PackingList> = {},
  items: PackingItem[] = [createItem()],
): PackingList & { items: PackingItem[] } => ({
  id: listId,
  tripId,
  name: '电子设备',
  category: PackingListCategory.electronics,
  createdAt: now,
  updatedAt: now,
  items,
  ...overrides,
});

describe('PackingListsService', () => {
  let service: PackingListsService;
  let repository: jest.Mocked<PackingListsRepository>;
  let tripMembersService: jest.Mocked<TripMembersService>;

  beforeEach(() => {
    repository = {
      findListsByTrip: jest.fn(),
      findListById: jest.fn(),
      findItemById: jest.fn(),
      createList: jest.fn(),
      updateList: jest.fn(),
      deleteList: jest.fn(),
      createItem: jest.fn(),
      updateItem: jest.fn(),
      deleteItem: jest.fn(),
      duplicateList: jest.fn(),
    } as unknown as jest.Mocked<PackingListsRepository>;
    tripMembersService = {
      requireTripMember: jest.fn().mockResolvedValue({ id: 'membership-1' }),
    } as unknown as jest.Mocked<TripMembersService>;
    service = new PackingListsService(repository, tripMembersService);
  });

  it('lists packing lists with progress', async () => {
    repository.findListsByTrip.mockResolvedValue([
      createList({}, [createItem({ isPacked: true }), createItem({ id: 'item-2' })]),
    ]);

    await expect(service.findAll(tripId, userId)).resolves.toEqual({
      packingLists: [expect.objectContaining({ packedCount: 1, itemCount: 2 })],
    });
  });

  it('creates a list from the default category template', async () => {
    repository.createList.mockResolvedValue(createList());

    await service.create(tripId, userId, {
      name: ' 电子设备 ',
      category: PackingListCategory.electronics,
    });

    expect(repository.createList).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '电子设备',
        category: PackingListCategory.electronics,
        items: {
          create: expect.arrayContaining([
            expect.objectContaining({ name: '手机充电器', quantity: 1, orderIndex: 0 }),
          ]),
        },
      }),
    );
  });

  it('updates and deletes a list', async () => {
    repository.findListById.mockResolvedValue(createList());
    repository.updateList.mockResolvedValue(createList({ name: '随身电子设备' }));
    repository.deleteList.mockResolvedValue(createList());

    await service.update(tripId, listId, userId, { name: '随身电子设备' });
    await expect(service.delete(tripId, listId, userId)).resolves.toEqual({ success: true });

    expect(repository.updateList).toHaveBeenCalledWith(
      listId,
      expect.objectContaining({ name: '随身电子设备' }),
    );
    expect(repository.deleteList).toHaveBeenCalledWith(listId);
  });

  it('creates an item at the end of the list', async () => {
    repository.findListById.mockResolvedValue(createList());
    repository.createItem.mockResolvedValue(createItem({ id: 'item-2', orderIndex: 1 }));

    await service.createItem(tripId, listId, userId, { name: '数据线', quantity: 2 });

    expect(repository.createItem).toHaveBeenCalledWith(
      listId,
      expect.objectContaining({ name: '数据线', quantity: 2, orderIndex: 1 }),
    );
  });

  it('toggles an item packed state', async () => {
    repository.findListById.mockResolvedValue(createList());
    repository.findItemById.mockResolvedValue(createItem());
    repository.updateItem.mockResolvedValue(createItem({ isPacked: true }));

    await expect(
      service.updateItem(tripId, listId, itemId, userId, { isPacked: true }),
    ).resolves.toEqual({ item: expect.objectContaining({ isPacked: true }) });
    expect(repository.updateItem).toHaveBeenCalledWith(itemId, { isPacked: true });
  });

  it('deletes an item', async () => {
    repository.findListById.mockResolvedValue(createList());
    repository.findItemById.mockResolvedValue(createItem());
    repository.deleteItem.mockResolvedValue(createItem());

    await expect(service.deleteItem(tripId, listId, itemId, userId)).resolves.toEqual({
      success: true,
    });
  });

  it('duplicates a list as an unpacked reusable copy', async () => {
    const source = createList();
    repository.findListById.mockResolvedValue(source);
    repository.duplicateList.mockResolvedValue(createList({ id: 'list-2', name: '电子设备 副本' }));

    await service.duplicate(tripId, listId, userId);

    expect(repository.duplicateList).toHaveBeenCalledWith(tripId, source, '电子设备 副本');
  });

  it('returns 403 for a non-member', async () => {
    tripMembersService.requireTripMember.mockRejectedValue(new NotFoundException());

    await expect(service.findAll(tripId, userId)).rejects.toBeInstanceOf(ForbiddenException);
  });

  it('rejects a list from another trip', async () => {
    repository.findListById.mockResolvedValue(createList({ tripId: 'trip-2' }));

    await expect(service.delete(tripId, listId, userId)).rejects.toBeInstanceOf(NotFoundException);
  });

  it('rejects an item from another list', async () => {
    repository.findListById.mockResolvedValue(createList());
    repository.findItemById.mockResolvedValue(createItem({ packingListId: 'list-2' }));

    await expect(
      service.updateItem(tripId, listId, itemId, userId, { isPacked: true }),
    ).rejects.toBeInstanceOf(NotFoundException);
  });

  it('rejects quantities below one', async () => {
    repository.findListById.mockResolvedValue(createList());

    await expect(
      service.createItem(tripId, listId, userId, { name: '数据线', quantity: 0 }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });
});
