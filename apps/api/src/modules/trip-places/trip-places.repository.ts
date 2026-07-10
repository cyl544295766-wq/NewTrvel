import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TripPlacesRepository {
  constructor(private readonly prisma: PrismaService) {}

  findDay(dayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: dayId } });
  }

  findPlace(placeId: string) {
    return this.prisma.tripPlace.findUnique({ where: { id: placeId } });
  }

  countPlacesInDay(tripDayId: string) {
    return this.prisma.tripPlace.count({ where: { tripDayId } });
  }

  create(data: Prisma.TripPlaceCreateInput) {
    return this.prisma.tripPlace.create({ data });
  }

  update(placeId: string, data: Prisma.TripPlaceUpdateInput) {
    return this.prisma.tripPlace.update({ where: { id: placeId }, data });
  }

  moveToDay(placeId: string, tripDayId: string, sortOrder: number) {
    return this.prisma.tripPlace.update({
      where: { id: placeId },
      data: {
        tripDayId,
        sortOrder,
      },
    });
  }

  delete(placeId: string) {
    return this.prisma.tripPlace.delete({ where: { id: placeId } });
  }

  findPlacesByIds(placeIds: string[]) {
    return this.prisma.tripPlace.findMany({ where: { id: { in: placeIds } } });
  }

  async reorder(placeIds: string[]) {
    return this.prisma.$transaction(
      placeIds.map((id, index) =>
        this.prisma.tripPlace.update({ where: { id }, data: { sortOrder: index } }),
      ),
    );
  }
}
