import { BadRequestException, Injectable } from '@nestjs/common';
import { TripExpenseCategory } from '@prisma/client';
import { expenseCategoryLabels } from '../trip-expenses/types/expense-category.type';
import { TravelStatsRepository, TravelStatsTrip } from './travel-stats.repository';

const primaryCurrency = 'CNY';
const monthLabels = [
  '一月',
  '二月',
  '三月',
  '四月',
  '五月',
  '六月',
  '七月',
  '八月',
  '九月',
  '十月',
  '十一月',
  '十二月',
];

@Injectable()
export class TravelStatsService {
  constructor(private readonly travelStatsRepository: TravelStatsRepository) {}

  async getOverview(userId: string) {
    const trips = await this.travelStatsRepository.findTripsForUser(userId);
    const summary = this.buildSummary(trips);
    return {
      ...summary,
      availableYears: this.getAvailableYears(trips),
      yearlyComparison: this.buildYearlyComparison(trips),
    };
  }

  async getYearly(userId: string, yearValue?: string) {
    const year = this.parseYear(yearValue);
    const trips = await this.travelStatsRepository.findTripsForUser(userId);
    return { year, ...this.buildSummary(this.filterByYear(trips, year)) };
  }

  async getMonthly(userId: string, yearValue?: string) {
    const year = this.parseYear(yearValue);
    const trips = this.filterByYear(
      await this.travelStatsRepository.findTripsForUser(userId),
      year,
    );
    return {
      year,
      primaryCurrency,
      months: monthLabels.map((label, index) => {
        const monthTrips = trips.filter((trip) => trip.startDate?.getMonth() === index);
        return {
          month: index + 1,
          label,
          tripCount: monthTrips.length,
          totalDays: this.sumTripDays(monthTrips),
          totalExpenseAmount: this.sumExpenses(monthTrips).toFixed(2),
        };
      }),
    };
  }

  async getExpenseCategories(userId: string) {
    const trips = await this.travelStatsRepository.findTripsForUser(userId);
    const totals = new Map<TripExpenseCategory, number>();
    for (const category of Object.values(TripExpenseCategory)) totals.set(category, 0);

    for (const trip of trips) {
      for (const expense of trip.expenses) {
        if (expense.currency !== primaryCurrency) continue;
        totals.set(expense.category, (totals.get(expense.category) ?? 0) + Number(expense.amount));
      }
    }
    const total = Array.from(totals.values()).reduce((sum, amount) => sum + amount, 0);
    return {
      primaryCurrency,
      totalExpenseAmount: total.toFixed(2),
      categories: Object.values(TripExpenseCategory).map((category) => {
        const amount = totals.get(category) ?? 0;
        return {
          category,
          label: expenseCategoryLabels[category],
          amount: amount.toFixed(2),
          percentage: total > 0 ? Number(((amount / total) * 100).toFixed(1)) : 0,
        };
      }),
    };
  }

  private buildSummary(trips: TravelStatsTrip[]) {
    const tripCount = trips.length;
    const totalDays = this.sumTripDays(trips);
    const totalExpenseAmount = this.sumExpenses(trips);
    const destinationCounts = new Map<string, { label: string; count: number }>();
    const monthCounts = Array.from({ length: 12 }, () => 0);

    for (const trip of trips) {
      const destination = trip.destination?.trim();
      if (destination) {
        const key = destination.toLocaleLowerCase('zh-CN');
        const current = destinationCounts.get(key);
        destinationCounts.set(key, {
          label: current?.label ?? destination,
          count: (current?.count ?? 0) + 1,
        });
      }
      if (trip.startDate) monthCounts[trip.startDate.getMonth()] += 1;
    }

    const mostVisitedDestination = [...destinationCounts.values()].sort(
      (left, right) => right.count - left.count || left.label.localeCompare(right.label, 'zh-CN'),
    )[0] ?? { label: null, count: 0 };
    const busiestMonthCount = Math.max(...monthCounts);
    const busiestMonthIndex = busiestMonthCount > 0 ? monthCounts.indexOf(busiestMonthCount) : -1;

    return {
      primaryCurrency,
      tripCount,
      totalDays,
      totalExpenseAmount: totalExpenseAmount.toFixed(2),
      destinationCount: destinationCounts.size,
      averageTripDays: tripCount > 0 ? Number((totalDays / tripCount).toFixed(1)) : 0,
      averageExpensePerTrip:
        tripCount > 0 ? (totalExpenseAmount / tripCount).toFixed(2) : '0.00',
      mostVisitedDestination,
      busiestMonth: {
        month: busiestMonthIndex + 1,
        label: busiestMonthIndex >= 0 ? monthLabels[busiestMonthIndex] : null,
        count: busiestMonthCount,
      },
    };
  }

  private buildYearlyComparison(trips: TravelStatsTrip[]) {
    return this.getAvailableYears(trips)
      .slice()
      .sort((left, right) => left - right)
      .map((year) => {
        const yearTrips = this.filterByYear(trips, year);
        return {
          year,
          tripCount: yearTrips.length,
          totalDays: this.sumTripDays(yearTrips),
          totalExpenseAmount: this.sumExpenses(yearTrips).toFixed(2),
        };
      });
  }

  private getAvailableYears(trips: TravelStatsTrip[]) {
    return [
      ...new Set(
        trips
          .map((trip) => trip.startDate?.getFullYear())
          .filter((year): year is number => year !== undefined),
      ),
    ].sort((left, right) => right - left);
  }

  private filterByYear(trips: TravelStatsTrip[], year: number) {
    return trips.filter((trip) => trip.startDate?.getFullYear() === year);
  }

  private sumTripDays(trips: TravelStatsTrip[]) {
    return trips.reduce((total, trip) => total + this.getTripDays(trip), 0);
  }

  private getTripDays(trip: TravelStatsTrip) {
    if (trip.days.length > 0) return new Set(trip.days.map((day) => dateKey(day.date))).size;
    if (!trip.startDate || !trip.endDate) return 0;
    const start = Date.UTC(
      trip.startDate.getFullYear(),
      trip.startDate.getMonth(),
      trip.startDate.getDate(),
    );
    const end = Date.UTC(
      trip.endDate.getFullYear(),
      trip.endDate.getMonth(),
      trip.endDate.getDate(),
    );
    return Math.max(Math.floor((end - start) / 86_400_000) + 1, 0);
  }

  private sumExpenses(trips: TravelStatsTrip[]) {
    return trips.reduce(
      (total, trip) =>
        total +
        trip.expenses.reduce(
          (tripTotal, expense) =>
            tripTotal + (expense.currency === primaryCurrency ? Number(expense.amount) : 0),
          0,
        ),
      0,
    );
  }

  private parseYear(value?: string) {
    const year = Number(value ?? new Date().getFullYear());
    if (!Number.isInteger(year) || year < 1900 || year > 2100) {
      throw new BadRequestException('年份格式不正确');
    }
    return year;
  }
}

function dateKey(value: Date) {
  return `${value.getFullYear()}-${value.getMonth() + 1}-${value.getDate()}`;
}
