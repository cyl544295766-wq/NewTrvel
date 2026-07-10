import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

export type TripPdfData = Prisma.TripGetPayload<{
  include: {
    days: { include: { places: true } };
    expenses: true;
    photos: { include: { tripDay: true; tripPlace: true } };
  };
}>;

@Injectable()
export class TripPdfRepository {
  constructor(private readonly prisma: PrismaService) {}

  findTripForExport(tripId: string) {
    return this.prisma.trip.findFirst({
      where: { id: tripId, deletedAt: null },
      include: {
        days: {
          include: { places: { orderBy: { sortOrder: 'asc' } } },
          orderBy: { dayIndex: 'asc' },
        },
        expenses: { orderBy: { spentAt: 'desc' } },
        photos: {
          include: { tripDay: true, tripPlace: true },
          orderBy: [{ isCover: 'desc' }, { shotAt: 'desc' }, { createdAt: 'desc' }],
        },
      },
    });
  }
}
