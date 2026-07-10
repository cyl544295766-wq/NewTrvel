import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class PhotosRepository {
  constructor(private readonly prisma: PrismaService) {}

  findPhotosByTrip(tripId: string) {
    return this.prisma.photo.findMany({
      where: { tripId },
      include: { tripDay: true, tripPlace: true },
      orderBy: [{ shotAt: 'desc' }, { createdAt: 'desc' }],
    });
  }

  findPhotoById(photoId: string) {
    return this.prisma.photo.findUnique({
      where: { id: photoId },
      include: { tripDay: true, tripPlace: true },
    });
  }

  countPhotosByTrip(tripId: string) {
    return this.prisma.photo.count({ where: { tripId } });
  }

  findTripDay(dayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: dayId } });
  }

  findTripPlace(placeId: string) {
    return this.prisma.tripPlace.findUnique({ where: { id: placeId } });
  }

  createPhoto(tripId: string, data: Prisma.PhotoCreateInput, isCover: boolean) {
    return this.prisma.$transaction(async (tx) => {
      if (isCover) {
        await tx.photo.updateMany({ where: { tripId }, data: { isCover: false } });
      }

      const photo = await tx.photo.create({
        data,
        include: { tripDay: true, tripPlace: true },
      });

      if (isCover) {
        await tx.trip.update({ where: { id: tripId }, data: { coverImageUrl: photo.url } });
      }

      return photo;
    });
  }

  updatePhoto(photoId: string, data: Prisma.PhotoUpdateInput, tripId: string, isCover?: boolean) {
    return this.prisma.$transaction(async (tx) => {
      const currentPhoto =
        isCover === false
          ? await tx.photo.findUnique({ where: { id: photoId }, select: { isCover: true } })
          : null;

      if (isCover) {
        await tx.photo.updateMany({ where: { tripId }, data: { isCover: false } });
      }

      const photo = await tx.photo.update({
        where: { id: photoId },
        data,
        include: { tripDay: true, tripPlace: true },
      });

      if (isCover) {
        await tx.trip.update({ where: { id: tripId }, data: { coverImageUrl: photo.url } });
      } else if (isCover === false && currentPhoto?.isCover) {
        await tx.trip.update({ where: { id: tripId }, data: { coverImageUrl: null } });
      }

      return photo;
    });
  }

  deletePhoto(photoId: string) {
    return this.prisma.$transaction(async (tx) => {
      const currentPhoto = await tx.photo.findUnique({
        where: { id: photoId },
        select: { isCover: true, tripId: true },
      });
      const photo = await tx.photo.delete({ where: { id: photoId } });

      if (currentPhoto?.isCover) {
        await tx.trip.update({
          where: { id: currentPhoto.tripId },
          data: { coverImageUrl: null },
        });
      }

      return photo;
    });
  }

  setCoverPhoto(tripId: string, photoId: string) {
    return this.prisma.$transaction(async (tx) => {
      await tx.photo.updateMany({ where: { tripId }, data: { isCover: false } });
      const photo = await tx.photo.update({ where: { id: photoId }, data: { isCover: true } });
      await tx.trip.update({ where: { id: tripId }, data: { coverImageUrl: photo.url } });
      return photo;
    });
  }

  findCoverPhotosForTrips(tripIds: string[]) {
    return this.prisma.photo.findMany({
      where: { tripId: { in: tripIds }, isCover: true },
      orderBy: { updatedAt: 'desc' },
    });
  }

  findRecentPhotosForTrips(tripIds: string[], take: number) {
    return this.prisma.photo.findMany({
      where: { tripId: { in: tripIds } },
      include: { trip: true },
      orderBy: [{ shotAt: 'desc' }, { createdAt: 'desc' }],
      take,
    });
  }
}
