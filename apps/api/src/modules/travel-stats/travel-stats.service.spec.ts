import { Prisma, TripExpenseCategory, TripStatus } from '@prisma/client';
import { TravelStatsRepository, TravelStatsTrip } from './travel-stats.repository';
import { TravelStatsService } from './travel-stats.service';

describe('TravelStatsService', () => {
  const repository = { findTripsForUser: jest.fn() };
  const service = new TravelStatsService(repository as unknown as TravelStatsRepository);

  beforeEach(() => {
    jest.clearAllMocks();
    repository.findTripsForUser.mockResolvedValue(createTrips());
  });

  it('calculates the overall travel statistics', async () => {
    const result = await service.getOverview('user-1');

    expect(result).toEqual(
      expect.objectContaining({
        primaryCurrency: 'CNY',
        tripCount: 3,
        totalDays: 6,
        totalExpenseAmount: '300.00',
        destinationCount: 2,
        averageTripDays: 2,
        averageExpensePerTrip: '100.00',
        mostVisitedDestination: { label: '上海', count: 2 },
      }),
    );
    expect(result.availableYears).toEqual([2026, 2025]);
  });

  it('calculates yearly and monthly statistics', async () => {
    const yearly = await service.getYearly('user-1', '2026');
    const monthly = await service.getMonthly('user-1', '2026');

    expect(yearly.tripCount).toBe(2);
    expect(yearly.totalExpenseAmount).toBe('300.00');
    expect(monthly.months[0]).toEqual(
      expect.objectContaining({ month: 1, tripCount: 1, totalDays: 2 }),
    );
    expect(monthly.months[2]).toEqual(
      expect.objectContaining({ month: 3, tripCount: 1, totalDays: 1 }),
    );
  });

  it('calculates expense category percentages in the primary currency', async () => {
    const result = await service.getExpenseCategories('user-1');
    const food = result.categories.find((item) => item.category === 'food');
    const hotel = result.categories.find((item) => item.category === 'hotel');

    expect(result.totalExpenseAmount).toBe('300.00');
    expect(food).toEqual(expect.objectContaining({ amount: '100.00', percentage: 33.3 }));
    expect(hotel).toEqual(expect.objectContaining({ amount: '200.00', percentage: 66.7 }));
  });

  it('returns zero values when the user has no trips', async () => {
    repository.findTripsForUser.mockResolvedValue([]);

    const overview = await service.getOverview('user-1');
    const categories = await service.getExpenseCategories('user-1');

    expect(overview.tripCount).toBe(0);
    expect(overview.totalDays).toBe(0);
    expect(overview.totalExpenseAmount).toBe('0.00');
    expect(overview.mostVisitedDestination).toEqual({ label: null, count: 0 });
    expect(categories.totalExpenseAmount).toBe('0.00');
    expect(categories.categories.every((item) => item.percentage === 0)).toBe(true);
  });
});

function createTrips(): TravelStatsTrip[] {
  return [
    createTrip({
      id: 'trip-1',
      title: '上海一月',
      destination: '上海',
      startDate: '2026-01-10',
      endDate: '2026-01-11',
      status: TripStatus.active,
      dayCount: 2,
      expenses: [
        { category: TripExpenseCategory.food, amount: 100, currency: 'CNY' },
        { category: TripExpenseCategory.shopping, amount: 50, currency: 'USD' },
      ],
    }),
    createTrip({
      id: 'trip-2',
      title: '杭州三月',
      destination: '杭州',
      startDate: '2026-03-05',
      endDate: '2026-03-05',
      status: TripStatus.archived,
      dayCount: 1,
      expenses: [{ category: TripExpenseCategory.hotel, amount: 200, currency: 'CNY' }],
    }),
    createTrip({
      id: 'trip-3',
      title: '上海旧游',
      destination: ' 上海 ',
      startDate: '2025-10-01',
      endDate: '2025-10-03',
      status: TripStatus.archived,
      dayCount: 3,
      expenses: [],
    }),
  ];
}

type TripFixture = {
  id: string;
  title: string;
  destination: string;
  startDate: string;
  endDate: string;
  status: TripStatus;
  dayCount: number;
  expenses: { category: TripExpenseCategory; amount: number; currency: string }[];
};

function createTrip(input: TripFixture): TravelStatsTrip {
  const now = new Date('2026-07-11T00:00:00.000Z');
  return {
    id: input.id,
    title: input.title,
    description: null,
    destination: input.destination,
    startDate: new Date(`${input.startDate}T00:00:00`),
    endDate: new Date(`${input.endDate}T00:00:00`),
    status: input.status,
    coverImageUrl: null,
    budget: null,
    ownerId: 'user-1',
    isFavorite: false,
    archivedAt: input.status === TripStatus.archived ? now : null,
    deletedAt: null,
    createdAt: now,
    updatedAt: now,
    days: Array.from({ length: input.dayCount }, (_, index) => ({
      id: `${input.id}-day-${index + 1}`,
      date: new Date(new Date(`${input.startDate}T00:00:00`).getTime() + index * 86_400_000),
    })),
    expenses: input.expenses.map((expense, index) => ({
      id: `${input.id}-expense-${index + 1}`,
      tripId: input.id,
      payerUserId: 'user-1',
      title: '旅行支出',
      amount: new Prisma.Decimal(expense.amount),
      currency: expense.currency,
      category: expense.category,
      spentAt: now,
      notes: null,
      createdAt: now,
      updatedAt: now,
    })),
  };
}
