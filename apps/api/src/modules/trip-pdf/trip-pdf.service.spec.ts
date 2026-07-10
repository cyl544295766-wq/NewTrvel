import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { Prisma, TripExpenseCategory, TripStatus } from '@prisma/client';
import { buildTripPdfDocument } from './trip-pdf.document';
import { TripPdfData, TripPdfRepository } from './trip-pdf.repository';
import { TripPdfService } from './trip-pdf.service';
import { TripMembersService } from '../trip-members/trip-members.service';

describe('TripPdfService', () => {
  const repository = { findTripForExport: jest.fn() };
  const membersService = { requireTripMember: jest.fn() };
  const service = new TripPdfService(
    repository as unknown as TripPdfRepository,
    membersService as unknown as TripMembersService,
  );

  beforeEach(() => {
    jest.clearAllMocks();
    membersService.requireTripMember.mockResolvedValue({ role: 'member' });
  });

  it('allows a member to export a PDF', async () => {
    repository.findTripForExport.mockResolvedValue(createTripPdfData());

    const result = await service.export('trip-1', 'user-1');

    expect(membersService.requireTripMember).toHaveBeenCalledWith('trip-1', 'user-1');
    expect(result.filename).toBe('上海周末.pdf');
    expect(result.buffer.subarray(0, 4).toString()).toBe('%PDF');
  });

  it('returns 403 when the user is not a trip member', async () => {
    repository.findTripForExport.mockResolvedValue(createTripPdfData());
    membersService.requireTripMember.mockRejectedValue(new NotFoundException('旅行不存在'));

    await expect(service.export('trip-1', 'user-2')).rejects.toBeInstanceOf(ForbiddenException);
  });

  it('returns 404 when the trip does not exist', async () => {
    repository.findTripForExport.mockResolvedValue(null);

    await expect(service.export('missing', 'user-1')).rejects.toBeInstanceOf(NotFoundException);
    expect(membersService.requireTripMember).not.toHaveBeenCalled();
  });

  it('includes the trip title, place and expense content in the PDF definition', () => {
    const definition = buildTripPdfDocument(createTripPdfData(), null, []);
    const content = JSON.stringify(definition.content);

    expect(content).toContain('上海周末');
    expect(content).toContain('外滩');
    expect(content).toContain('餐饮');
    expect(content).toContain('128.00');
  });
});

function createTripPdfData(): TripPdfData {
  const now = new Date('2026-07-11T00:00:00.000Z');
  return {
    id: 'trip-1',
    title: '上海周末',
    description: '城市漫步',
    destination: '上海',
    startDate: now,
    endDate: new Date('2026-07-12T00:00:00.000Z'),
    status: TripStatus.planning,
    coverImageUrl: null,
    budget: new Prisma.Decimal(1000),
    ownerId: 'user-1',
    isFavorite: false,
    archivedAt: null,
    deletedAt: null,
    createdAt: now,
    updatedAt: now,
    days: [
      {
        id: 'day-1',
        tripId: 'trip-1',
        date: now,
        dayIndex: 1,
        title: '城市漫步',
        summary: '沿江游览',
        createdAt: now,
        updatedAt: now,
        places: [
          {
            id: 'place-1',
            tripId: 'trip-1',
            tripDayId: 'day-1',
            name: '外滩',
            type: 'attraction',
            address: '中山东一路',
            latitude: null,
            longitude: null,
            startTime: new Date('2026-07-11T09:00:00.000Z'),
            endTime: new Date('2026-07-11T11:00:00.000Z'),
            notes: '步行游览',
            sortOrder: 0,
            isCompleted: false,
            createdAt: now,
            updatedAt: now,
          },
        ],
      },
    ],
    expenses: [
      {
        id: 'expense-1',
        tripId: 'trip-1',
        payerUserId: 'user-1',
        title: '午餐',
        amount: new Prisma.Decimal(128),
        currency: 'CNY',
        category: TripExpenseCategory.food,
        spentAt: now,
        notes: null,
        createdAt: now,
        updatedAt: now,
      },
    ],
    photos: [],
  };
}
