import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TripDaysRepository {
  constructor(private readonly prisma: PrismaService) {}

  findTrip(tripId: string) {
    return this.prisma.trip.findUnique({ where: { id: tripId } });
  }

  findAllByTrip(tripId: string) {
    return this.prisma.tripDay.findMany({
      where: { tripId },
      include: { places: { orderBy: { sortOrder: 'asc' } } },
      orderBy: { dayIndex: 'asc' },
    });
  }

  findById(dayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: dayId } });
  }

  createMany(data: Prisma.TripDayCreateManyInput[]) {
    return this.prisma.tripDay.createMany({ data, skipDuplicates: true });
  }

  update(dayId: string, data: Prisma.TripDayUpdateInput) {
    return this.prisma.tripDay.update({ where: { id: dayId }, data });
  }
}
