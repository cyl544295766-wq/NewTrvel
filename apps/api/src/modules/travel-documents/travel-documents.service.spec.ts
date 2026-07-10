import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { TravelDocument, TravelDocumentType } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { TravelDocumentsRepository } from './travel-documents.repository';
import { TravelDocumentsService } from './travel-documents.service';

const tripId = 'trip-1';
const userId = 'user-1';
const documentId = 'document-1';
const now = new Date('2026-07-10T08:00:00.000Z');

type DocumentWithRelations = TravelDocument & { tripDay: null; tripPlace: null };

const createDocument = (overrides: Partial<TravelDocument> = {}): DocumentWithRelations => ({
  id: documentId,
  tripId,
  tripDayId: null,
  tripPlaceId: null,
  type: TravelDocumentType.passport,
  title: '护照首页',
  url: 'data:application/pdf;base64,YQ==',
  notes: null,
  expiredAt: null,
  isReminder: false,
  createdAt: now,
  updatedAt: now,
  tripDay: null,
  tripPlace: null,
  ...overrides,
});

describe('TravelDocumentsService', () => {
  let service: TravelDocumentsService;
  let repository: jest.Mocked<TravelDocumentsRepository>;
  let tripMembersService: jest.Mocked<TripMembersService>;

  beforeEach(() => {
    repository = {
      findDocumentsByTrip: jest.fn(),
      findDocumentById: jest.fn(),
      countDocumentsByTrip: jest.fn(),
      findTripDay: jest.fn(),
      findTripPlace: jest.fn(),
      createDocument: jest.fn(),
      updateDocument: jest.fn(),
      deleteDocument: jest.fn(),
    } as unknown as jest.Mocked<TravelDocumentsRepository>;
    tripMembersService = {
      requireTripMember: jest.fn().mockResolvedValue({ id: 'membership-1' }),
    } as unknown as jest.Mocked<TripMembersService>;
    service = new TravelDocumentsService(repository, tripMembersService);
  });

  it('lists documents for a trip member', async () => {
    repository.findDocumentsByTrip.mockResolvedValue([createDocument()]);

    await expect(service.findAll(tripId, userId)).resolves.toEqual({
      documents: [expect.objectContaining({ id: documentId, title: '护照首页' })],
    });
  });

  it('returns one document', async () => {
    repository.findDocumentById.mockResolvedValue(createDocument());

    await expect(service.findOne(tripId, documentId, userId)).resolves.toEqual({
      document: expect.objectContaining({ id: documentId }),
    });
  });

  it('creates a valid document', async () => {
    const document = createDocument({ isReminder: true });
    repository.countDocumentsByTrip.mockResolvedValue(0);
    repository.createDocument.mockResolvedValue(document);

    await expect(
      service.create(tripId, userId, {
        type: TravelDocumentType.passport,
        title: ' 护照首页 ',
        url: document.url,
        isReminder: true,
      }),
    ).resolves.toEqual({ document: expect.objectContaining({ id: documentId }) });
    expect(repository.createDocument).toHaveBeenCalledWith(
      expect.objectContaining({ title: '护照首页', url: document.url, isReminder: true }),
    );
  });

  it('updates document metadata and relations', async () => {
    repository.findDocumentById.mockResolvedValue(createDocument());
    repository.findTripDay.mockResolvedValue({ tripId } as never);
    repository.updateDocument.mockResolvedValue(
      createDocument({ type: TravelDocumentType.visa, title: '日本签证', tripDayId: 'day-1' }),
    );

    await service.update(tripId, documentId, userId, {
      type: TravelDocumentType.visa,
      title: '日本签证',
      tripDayId: 'day-1',
      isReminder: true,
    });

    expect(repository.updateDocument).toHaveBeenCalledWith(
      documentId,
      expect.objectContaining({
        type: TravelDocumentType.visa,
        title: '日本签证',
        tripDay: { connect: { id: 'day-1' } },
        isReminder: true,
      }),
    );
  });

  it('deletes an existing document', async () => {
    repository.findDocumentById.mockResolvedValue(createDocument());
    repository.deleteDocument.mockResolvedValue(createDocument());

    await expect(service.delete(tripId, documentId, userId)).resolves.toEqual({ success: true });
    expect(repository.deleteDocument).toHaveBeenCalledWith(documentId);
  });

  it('returns 403 for a non-member', async () => {
    tripMembersService.requireTripMember.mockRejectedValue(new NotFoundException());

    await expect(service.findAll(tripId, userId)).rejects.toBeInstanceOf(ForbiddenException);
    expect(repository.findDocumentsByTrip).not.toHaveBeenCalled();
  });

  it('rejects unsupported files', async () => {
    await expect(
      service.create(tripId, userId, {
        type: TravelDocumentType.other,
        title: '压缩包',
        url: 'data:application/zip;base64,YQ==',
      }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects a blank title after trimming', async () => {
    repository.countDocumentsByTrip.mockResolvedValue(0);

    await expect(
      service.create(tripId, userId, {
        type: TravelDocumentType.other,
        title: '   ',
        url: 'data:application/pdf;base64,YQ==',
      }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects files larger than 10 MB', async () => {
    const oversized = Buffer.alloc(10 * 1024 * 1024 + 1).toString('base64');

    await expect(
      service.create(tripId, userId, {
        type: TravelDocumentType.ticket,
        title: '门票',
        url: `data:application/pdf;base64,${oversized}`,
      }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects the 101st document in a trip', async () => {
    repository.countDocumentsByTrip.mockResolvedValue(100);

    await expect(
      service.create(tripId, userId, {
        type: TravelDocumentType.insurance,
        title: '保险单',
        url: 'data:application/pdf;base64,YQ==',
      }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects a day from another trip', async () => {
    repository.findDocumentById.mockResolvedValue(createDocument());
    repository.findTripDay.mockResolvedValue({ tripId: 'trip-2' } as never);

    await expect(
      service.update(tripId, documentId, userId, { tripDayId: 'day-2' }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });

  it('rejects a place from another trip', async () => {
    repository.findDocumentById.mockResolvedValue(createDocument());
    repository.findTripPlace.mockResolvedValue({ tripId: 'trip-2' } as never);

    await expect(
      service.update(tripId, documentId, userId, { tripPlaceId: 'place-2' }),
    ).rejects.toBeInstanceOf(BadRequestException);
  });
});
