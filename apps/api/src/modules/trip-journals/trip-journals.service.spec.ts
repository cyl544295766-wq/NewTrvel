import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { Photo, TripJournal } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { TripJournalsRepository } from './trip-journals.repository';
import { TripJournalsService } from './trip-journals.service';

const tripId = 'trip-1';
const userId = 'user-1';
const journalId = 'journal-1';
const now = new Date('2026-07-10T08:00:00.000Z');

const createPhoto = (id = 'photo-1', overrides: Partial<Photo> = {}): Photo => ({
  id,
  tripId,
  tripDayId: null,
  tripPlaceId: null,
  url: 'data:image/jpeg;base64,YQ==',
  caption: '旅行照片',
  shotAt: null,
  isCover: false,
  createdAt: now,
  updatedAt: now,
  ...overrides,
});

function createJournal(overrides: Partial<TripJournal> = {}, photos = [createPhoto()]) {
  return {
    id: journalId,
    tripId,
    tripDayId: null,
    tripPlaceId: null,
    title: '抵达东京',
    content: '# 第一天\n顺利抵达东京。',
    isDraft: true,
    mood: 'excited',
    createdAt: now,
    updatedAt: now,
    tripDay: null,
    tripPlace: null,
    photos: photos.map((photo, orderIndex) => ({
      journalId,
      photoId: photo.id,
      orderIndex,
      photo,
    })),
    ...overrides,
  };
}

describe('TripJournalsService', () => {
  let service: TripJournalsService;
  let repository: jest.Mocked<TripJournalsRepository>;
  let tripMembersService: jest.Mocked<TripMembersService>;

  beforeEach(() => {
    repository = {
      findJournalsByTrip: jest.fn(),
      findJournalById: jest.fn(),
      findTripDay: jest.fn(),
      findTripPlace: jest.fn(),
      findPhotosByIds: jest.fn(),
      createJournal: jest.fn(),
      updateJournal: jest.fn(),
      deleteJournal: jest.fn(),
      reorderPhotos: jest.fn(),
    } as unknown as jest.Mocked<TripJournalsRepository>;
    tripMembersService = {
      requireTripMember: jest.fn().mockResolvedValue({ id: 'membership-1' }),
    } as unknown as jest.Mocked<TripMembersService>;
    service = new TripJournalsService(repository, tripMembersService);
  });

  it('lists journals for a trip member', async () => {
    repository.findJournalsByTrip.mockResolvedValue([createJournal()] as never);

    await expect(service.findAll(tripId, userId)).resolves.toEqual({
      journals: [expect.objectContaining({ id: journalId, title: '抵达东京' })],
    });
  });

  it('returns one journal with associated photos', async () => {
    repository.findJournalById.mockResolvedValue(createJournal() as never);

    await expect(service.findOne(tripId, journalId, userId)).resolves.toEqual({
      journal: expect.objectContaining({
        id: journalId,
        photos: [expect.objectContaining({ id: 'photo-1', orderIndex: 0 })],
      }),
    });
  });

  it('creates a journal with photos', async () => {
    const photo = createPhoto();
    repository.findPhotosByIds.mockResolvedValue([photo]);
    repository.createJournal.mockResolvedValue(createJournal() as never);

    await service.create(tripId, userId, {
      title: ' 抵达东京 ',
      content: '# 第一天',
      mood: 'excited',
      isDraft: true,
      photoIds: [photo.id],
    });

    expect(repository.createJournal).toHaveBeenCalledWith(
      expect.objectContaining({ title: '抵达东京', isDraft: true, mood: 'excited' }),
      [photo.id],
    );
  });

  it('publishes a draft journal', async () => {
    repository.findJournalById.mockResolvedValue(createJournal() as never);
    repository.updateJournal.mockResolvedValue(createJournal({ isDraft: false }) as never);

    await expect(service.update(tripId, journalId, userId, { isDraft: false })).resolves.toEqual({
      journal: expect.objectContaining({ isDraft: false }),
    });
    expect(repository.updateJournal).toHaveBeenCalledWith(
      journalId,
      expect.objectContaining({ isDraft: false }),
      undefined,
    );
  });

  it('deletes a journal without deleting its photos', async () => {
    repository.findJournalById.mockResolvedValue(createJournal() as never);
    repository.deleteJournal.mockResolvedValue(createJournal() as never);

    await expect(service.delete(tripId, journalId, userId)).resolves.toEqual({ success: true });
    expect(repository.deleteJournal).toHaveBeenCalledWith(journalId);
  });

  it('reorders the currently associated photos', async () => {
    const photos = [createPhoto('photo-1'), createPhoto('photo-2')];
    repository.findJournalById.mockResolvedValue(createJournal({}, photos) as never);
    repository.findPhotosByIds.mockResolvedValue(photos);
    repository.reorderPhotos.mockResolvedValue(createJournal({}, [...photos].reverse()) as never);

    await service.reorderPhotos(tripId, journalId, userId, ['photo-2', 'photo-1']);

    expect(repository.reorderPhotos).toHaveBeenCalledWith(journalId, ['photo-2', 'photo-1']);
  });

  it('returns 403 for a non-member', async () => {
    tripMembersService.requireTripMember.mockRejectedValue(new NotFoundException());

    await expect(service.findAll(tripId, userId)).rejects.toBeInstanceOf(ForbiddenException);
  });

  it('rejects photos from another trip', async () => {
    repository.findPhotosByIds.mockResolvedValue([createPhoto('photo-2', { tripId: 'trip-2' })]);

    await expect(
      service.create(tripId, userId, {
        title: '游记',
        content: '',
        photoIds: ['photo-2'],
      }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects a reorder that changes the associated photo set', async () => {
    repository.findJournalById.mockResolvedValue(createJournal() as never);
    repository.findPhotosByIds.mockResolvedValue([createPhoto('photo-2')]);

    await expect(
      service.reorderPhotos(tripId, journalId, userId, ['photo-2']),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects invalid mood and oversized content', async () => {
    await expect(
      service.create(tripId, userId, { title: '游记', content: '', mood: 'angry' }),
    ).rejects.toBeInstanceOf(BadRequestException);
    await expect(
      service.create(tripId, userId, { title: '游记', content: 'A'.repeat(20001) }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });
});
