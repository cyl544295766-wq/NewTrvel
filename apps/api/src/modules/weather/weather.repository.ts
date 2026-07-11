import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class WeatherRepository {
  constructor(private readonly prisma: PrismaService) {}

  findTrip(tripId: string) {
    return this.prisma.trip.findFirst({ where: { id: tripId, deletedAt: null } });
  }

  findTripDay(tripDayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: tripDayId } });
  }

  findTripDays(tripId: string) {
    return this.prisma.tripDay.findMany({ where: { tripId }, orderBy: { date: 'asc' } });
  }

  findFreshWeather(tripId: string, destination: string, freshAfter: Date) {
    return this.prisma.tripWeather.findMany({
      where: { tripId, destination, fetchedAt: { gte: freshAfter } },
      orderBy: { date: 'asc' },
    });
  }

  async replaceForecast(tripId: string, rows: Prisma.TripWeatherCreateManyInput[]) {
    if (rows.length === 0) return;
    const dates = rows.map((row) => new Date(row.date));
    await this.prisma.$transaction(async (tx) => {
      await tx.tripWeather.deleteMany({ where: { tripId, date: { in: dates } } });
      await tx.tripWeather.createMany({ data: rows });
    });
  }
}
