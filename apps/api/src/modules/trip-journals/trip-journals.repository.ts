import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

const journalInclude = {
  tripDay: true,
  tripPlace: true,
  photos: { include: { photo: true }, orderBy: { orderIndex: 'asc' as const } },
};

@Injectable()
export class TripJournalsRepository {
  constructor(private readonly prisma: PrismaService) {}

  findJournalsByTrip(tripId: string) {
    return this.prisma.tripJournal.findMany({
      where: { tripId },
      include: journalInclude,
      orderBy: { createdAt: 'desc' },
    });
  }

  findJournalById(journalId: string) {
    return this.prisma.tripJournal.findUnique({
      where: { id: journalId },
      include: journalInclude,
    });
  }

  findTripDay(dayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: dayId } });
  }

  findTripPlace(placeId: string) {
    return this.prisma.tripPlace.findUnique({ where: { id: placeId } });
  }

  findPhotosByIds(photoIds: string[]) {
    return this.prisma.photo.findMany({ where: { id: { in: photoIds } } });
  }

  createJournal(data: Prisma.TripJournalCreateInput, photoIds: string[]) {
    return this.prisma.tripJournal.create({
      data: {
        ...data,
        photos: {
          create: photoIds.map((photoId, orderIndex) => ({
            photo: { connect: { id: photoId } },
            orderIndex,
          })),
        },
      },
      include: journalInclude,
    });
  }

  updateJournal(journalId: string, data: Prisma.TripJournalUpdateInput, photoIds?: string[]) {
    return this.prisma.$transaction(async (tx) => {
      await tx.tripJournal.update({ where: { id: journalId }, data });
      if (photoIds !== undefined) {
        await tx.tripJournalPhoto.deleteMany({ where: { journalId } });
        if (photoIds.length > 0) {
          await tx.tripJournalPhoto.createMany({
            data: photoIds.map((photoId, orderIndex) => ({ journalId, photoId, orderIndex })),
          });
        }
      }
      return tx.tripJournal.findUniqueOrThrow({
        where: { id: journalId },
        include: journalInclude,
      });
    });
  }

  deleteJournal(journalId: string) {
    return this.prisma.tripJournal.delete({ where: { id: journalId } });
  }

  reorderPhotos(journalId: string, photoIds: string[]) {
    return this.prisma.$transaction(async (tx) => {
      await tx.tripJournalPhoto.deleteMany({ where: { journalId } });
      if (photoIds.length > 0) {
        await tx.tripJournalPhoto.createMany({
          data: photoIds.map((photoId, orderIndex) => ({ journalId, photoId, orderIndex })),
        });
      }
      return tx.tripJournal.findUniqueOrThrow({
        where: { id: journalId },
        include: journalInclude,
      });
    });
  }

  findRecentPublishedForTrips(tripIds: string[], take: number) {
    return this.prisma.tripJournal.findMany({
      where: { tripId: { in: tripIds }, isDraft: false },
      include: { trip: true },
      orderBy: { createdAt: 'desc' },
      take,
    });
  }
}
