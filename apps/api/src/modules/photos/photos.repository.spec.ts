import { PhotosRepository } from './photos.repository';

describe('PhotosRepository', () => {
  const tripId = 'trip-1';
  const photoId = 'photo-1';
  let repository: PhotosRepository;
  let transactionPhoto: {
    updateMany: jest.Mock;
    findUnique: jest.Mock;
    create: jest.Mock;
    update: jest.Mock;
    delete: jest.Mock;
  };
  let transactionTrip: {
    update: jest.Mock;
  };
  let prisma: {
    $transaction: jest.Mock;
  };

  beforeEach(() => {
    transactionPhoto = {
      updateMany: jest.fn().mockResolvedValue({ count: 1 }),
      findUnique: jest.fn(),
      create: jest.fn().mockResolvedValue({ id: photoId, isCover: true, url: 'cover-url' }),
      update: jest.fn().mockResolvedValue({ id: photoId, isCover: true, url: 'cover-url' }),
      delete: jest.fn().mockResolvedValue({ id: photoId }),
    };
    transactionTrip = {
      update: jest.fn().mockResolvedValue({ id: tripId }),
    };
    prisma = {
      $transaction: jest.fn((callback) =>
        callback({ photo: transactionPhoto, trip: transactionTrip }),
      ),
    };
    repository = new PhotosRepository(prisma as never);
  });

  it('clears the existing cover before creating a new cover', async () => {
    await repository.createPhoto(
      tripId,
      { trip: { connect: { id: tripId } }, url: 'data:image/jpeg;base64,YQ==' },
      true,
    );

    expect(transactionPhoto.updateMany).toHaveBeenCalledWith({
      where: { tripId },
      data: { isCover: false },
    });
    expect(transactionPhoto.updateMany.mock.invocationCallOrder[0]).toBeLessThan(
      transactionPhoto.create.mock.invocationCallOrder[0],
    );
    expect(transactionTrip.update).toHaveBeenCalledWith({
      where: { id: tripId },
      data: { coverImageUrl: 'cover-url' },
    });
  });

  it('clears the existing cover before setting another photo as cover', async () => {
    await repository.updatePhoto(photoId, { isCover: true }, tripId, true);

    expect(transactionPhoto.updateMany).toHaveBeenCalledWith({
      where: { tripId },
      data: { isCover: false },
    });
    expect(transactionPhoto.update).toHaveBeenCalledWith(
      expect.objectContaining({ where: { id: photoId }, data: { isCover: true } }),
    );
    expect(transactionPhoto.updateMany.mock.invocationCallOrder[0]).toBeLessThan(
      transactionPhoto.update.mock.invocationCallOrder[0],
    );
    expect(transactionTrip.update).toHaveBeenCalledWith({
      where: { id: tripId },
      data: { coverImageUrl: 'cover-url' },
    });
  });

  it('clears the trip cover URL when the current cover is unset', async () => {
    transactionPhoto.findUnique.mockResolvedValue({ isCover: true });
    transactionPhoto.update.mockResolvedValue({ id: photoId, isCover: false, url: 'cover-url' });

    await repository.updatePhoto(photoId, { isCover: false }, tripId, false);

    expect(transactionTrip.update).toHaveBeenCalledWith({
      where: { id: tripId },
      data: { coverImageUrl: null },
    });
  });

  it('keeps the trip cover URL when a non-cover photo is updated', async () => {
    transactionPhoto.findUnique.mockResolvedValue({ isCover: false });

    await repository.updatePhoto(photoId, { isCover: false }, tripId, false);

    expect(transactionTrip.update).not.toHaveBeenCalled();
  });

  it('clears the trip cover URL when the current cover is deleted', async () => {
    transactionPhoto.findUnique.mockResolvedValue({ isCover: true, tripId });

    await repository.deletePhoto(photoId);

    expect(transactionPhoto.delete).toHaveBeenCalledWith({ where: { id: photoId } });
    expect(transactionTrip.update).toHaveBeenCalledWith({
      where: { id: tripId },
      data: { coverImageUrl: null },
    });
  });
});
