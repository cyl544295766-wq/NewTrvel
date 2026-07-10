import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { Photo } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { PhotosRepository } from './photos.repository';
import { PhotosService } from './photos.service';

const tripId = 'trip-1';
const userId = 'user-1';
const photoId = 'photo-1';
const now = new Date('2026-07-10T08:00:00.000Z');

type PhotoWithRelations = Photo & { tripDay: null; tripPlace: null };

const createPhoto = (overrides: Partial<Photo> = {}): PhotoWithRelations => ({
  id: photoId,
  tripId,
  tripDayId: null,
  tripPlaceId: null,
  url: 'data:image/jpeg;base64,YQ==',
  caption: null,
  shotAt: null,
  isCover: false,
  createdAt: now,
  updatedAt: now,
  tripDay: null,
  tripPlace: null,
  ...overrides,
});

describe('PhotosService', () => {
  let service: PhotosService;
  let repository: jest.Mocked<PhotosRepository>;
  let tripMembersService: jest.Mocked<TripMembersService>;

  beforeEach(() => {
    repository = {
      findPhotosByTrip: jest.fn(),
      findPhotoById: jest.fn(),
      countPhotosByTrip: jest.fn(),
      findTripDay: jest.fn(),
      findTripPlace: jest.fn(),
      createPhoto: jest.fn(),
      updatePhoto: jest.fn(),
      deletePhoto: jest.fn(),
    } as unknown as jest.Mocked<PhotosRepository>;
    tripMembersService = {
      requireTripMember: jest.fn().mockResolvedValue({ id: 'membership-1' }),
    } as unknown as jest.Mocked<TripMembersService>;
    service = new PhotosService(repository, tripMembersService);
  });

  describe('create', () => {
    it.each(['jpeg', 'jpg', 'png', 'webp'])('creates a %s photo', async (format) => {
      const photo = createPhoto({ url: `data:image/${format};base64,YQ==` });
      repository.countPhotosByTrip.mockResolvedValue(0);
      repository.createPhoto.mockResolvedValue(photo);

      await expect(service.create(tripId, userId, { url: photo.url })).resolves.toEqual({
        photo: expect.objectContaining({ id: photoId, url: photo.url }),
      });
      expect(repository.createPhoto).toHaveBeenCalledWith(
        tripId,
        expect.objectContaining({
          trip: { connect: { id: tripId } },
          url: photo.url,
          isCover: false,
        }),
        false,
      );
    });

    it('rejects unsupported image formats', async () => {
      await expect(
        service.create(tripId, userId, { url: 'data:image/gif;base64,YQ==' }),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(repository.countPhotosByTrip).not.toHaveBeenCalled();
    });

    it('rejects a photo larger than 5 MB', async () => {
      const oversizedBase64 = 'A'.repeat(Math.ceil(((5 * 1024 * 1024 + 1) * 4) / 3));

      await expect(
        service.create(tripId, userId, {
          url: `data:image/jpeg;base64,${oversizedBase64}`,
        }),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(repository.countPhotosByTrip).not.toHaveBeenCalled();
    });

    it('rejects the 501st photo in a trip', async () => {
      repository.countPhotosByTrip.mockResolvedValue(500);

      await expect(
        service.create(tripId, userId, { url: 'data:image/png;base64,YQ==' }),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(repository.createPhoto).not.toHaveBeenCalled();
    });
  });

  describe('findAll', () => {
    it('allows a trip member to view photos', async () => {
      repository.findPhotosByTrip.mockResolvedValue([createPhoto()]);

      await expect(service.findAll(tripId, userId)).resolves.toEqual({
        photos: [expect.objectContaining({ id: photoId, tripId })],
      });
      expect(repository.findPhotosByTrip).toHaveBeenCalledWith(tripId);
    });

    it('returns 403 for a non-member', async () => {
      tripMembersService.requireTripMember.mockRejectedValue(new NotFoundException());

      await expect(service.findAll(tripId, userId)).rejects.toBeInstanceOf(ForbiddenException);
      expect(repository.findPhotosByTrip).not.toHaveBeenCalled();
    });
  });

  describe('update', () => {
    beforeEach(() => {
      repository.findPhotoById.mockResolvedValue(createPhoto());
    });

    it('rejects a day from another trip', async () => {
      repository.findTripDay.mockResolvedValue({ tripId: 'trip-2' } as never);

      await expect(
        service.update(tripId, photoId, userId, { tripDayId: 'day-2' }),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(repository.updatePhoto).not.toHaveBeenCalled();
    });

    it('rejects a place from another trip', async () => {
      repository.findTripPlace.mockResolvedValue({ tripId: 'trip-2' } as never);

      await expect(
        service.update(tripId, photoId, userId, { tripPlaceId: 'place-2' }),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(repository.updatePhoto).not.toHaveBeenCalled();
    });
  });

  describe('delete', () => {
    it('allows a trip member to delete a photo', async () => {
      repository.findPhotoById.mockResolvedValue(createPhoto());
      repository.deletePhoto.mockResolvedValue(createPhoto());

      await expect(service.delete(tripId, photoId, userId)).resolves.toEqual({ success: true });
      expect(repository.deletePhoto).toHaveBeenCalledWith(photoId);
    });

    it('returns 403 for a non-member', async () => {
      tripMembersService.requireTripMember.mockRejectedValue(new NotFoundException());

      await expect(service.delete(tripId, photoId, userId)).rejects.toBeInstanceOf(
        ForbiddenException,
      );
      expect(repository.findPhotoById).not.toHaveBeenCalled();
      expect(repository.deletePhoto).not.toHaveBeenCalled();
    });
  });
});
