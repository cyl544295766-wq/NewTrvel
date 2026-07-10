import { Injectable } from '@nestjs/common';
import { Prisma, TripStatus } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TripsRepository {
  constructor(private readonly prisma: PrismaService) {}

  async createWithOwner(data: Prisma.TripCreateInput, ownerId: string) {
    return this.prisma.$transaction(async (tx) => {
      const trip = await tx.trip.create({ data });

      await tx.tripMember.create({
        data: {
          tripId: trip.id,
          userId: ownerId,
          role: 'owner',
        },
      });

      return trip;
    });
  }

  findById(id: string) {
    return this.prisma.trip.findFirst({ where: { id, deletedAt: null } });
  }

  findCoverPhoto(tripId: string) {
    return this.prisma.photo.findFirst({
      where: { tripId, isCover: true },
      orderBy: { updatedAt: 'desc' },
    });
  }

  findCoverPhotosForTrips(tripIds: string[]) {
    return this.prisma.photo.findMany({
      where: { tripId: { in: tripIds }, isCover: true },
      orderBy: { updatedAt: 'desc' },
    });
  }

  findTripsForUser(userId: string) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          status: { not: TripStatus.archived },
          archivedAt: null,
          deletedAt: null,
        },
      },
      include: { trip: true },
      orderBy: { trip: { updatedAt: 'desc' } },
    });
  }

  findDashboardTripsForUser(userId: string) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          deletedAt: null,
        },
      },
      include: { trip: true },
    });
  }

  findRecentTripsForUser(userId: string, take: number) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          deletedAt: null,
        },
      },
      include: { trip: true },
      orderBy: [{ trip: { isFavorite: 'desc' } }, { trip: { updatedAt: 'desc' } }],
      take,
    });
  }

  findUpcomingTripsForUser(userId: string, today: Date, take: number) {
    return this.prisma.tripMember.findMany({
      where: {
        userId,
        trip: {
          startDate: { gte: today },
          status: { not: TripStatus.archived },
          archivedAt: null,
          deletedAt: null,
        },
      },
      include: { trip: true },
      orderBy: { trip: { startDate: 'asc' } },
      take,
    });
  }

  update(id: string, data: Prisma.TripUpdateInput) {
    return this.prisma.trip.update({
      where: { id },
      data,
    });
  }

  softDelete(id: string) {
    return this.prisma.trip.update({
      where: { id },
      data: { deletedAt: new Date() },
    });
  }

  findByIdWithDaysAndPlaces(id: string) {
    return this.prisma.trip.findFirst({
      where: { id, deletedAt: null },
      include: {
        days: { orderBy: { dayIndex: 'asc' } },
        places: { orderBy: { sortOrder: 'asc' } },
      },
    });
  }

  findRouteDays(tripId: string) {
    return this.prisma.tripDay.findMany({
      where: { tripId },
      include: {
        places: {
          where: {
            latitude: { not: null },
            longitude: { not: null },
          },
          orderBy: { sortOrder: 'asc' },
        },
      },
      orderBy: { dayIndex: 'asc' },
    });
  }

  duplicateWithStructure(id: string, ownerId: string) {
    return this.prisma.$transaction(async (tx) => {
      const sourceTrip = await tx.trip.findFirst({
        where: { id, deletedAt: null },
        include: {
          days: { orderBy: { dayIndex: 'asc' } },
          places: { orderBy: { sortOrder: 'asc' } },
        },
      });

      if (!sourceTrip) {
        return null;
      }

      const trip = await tx.trip.create({
        data: {
          title: `${sourceTrip.title} 副本`,
          description: sourceTrip.description,
          destination: sourceTrip.destination,
          startDate: sourceTrip.startDate,
          endDate: sourceTrip.endDate,
          status: TripStatus.draft,
          coverImageUrl: sourceTrip.coverImageUrl,
          ownerId,
        },
      });

      await tx.tripMember.create({
        data: {
          tripId: trip.id,
          userId: ownerId,
          role: 'owner',
        },
      });

      const dayIdMap = new Map<string, string>();

      for (const day of sourceTrip.days) {
        const newDay = await tx.tripDay.create({
          data: {
            tripId: trip.id,
            date: day.date,
            dayIndex: day.dayIndex,
            title: day.title,
            summary: day.summary,
          },
        });

        dayIdMap.set(day.id, newDay.id);
      }

      for (const place of sourceTrip.places) {
        await tx.tripPlace.create({
          data: {
            tripId: trip.id,
            tripDayId: place.tripDayId ? dayIdMap.get(place.tripDayId) : null,
            name: place.name,
            type: place.type,
            address: place.address,
            latitude: place.latitude,
            longitude: place.longitude,
            notes: place.notes,
            sortOrder: place.sortOrder,
          },
        });
      }

      return trip;
    });
  }
}
