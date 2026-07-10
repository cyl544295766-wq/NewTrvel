import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TravelDocumentsRepository {
  constructor(private readonly prisma: PrismaService) {}

  findDocumentsByTrip(tripId: string) {
    return this.prisma.travelDocument.findMany({
      where: { tripId },
      include: { tripDay: true, tripPlace: true },
      orderBy: { createdAt: 'desc' },
    });
  }

  findDocumentById(documentId: string) {
    return this.prisma.travelDocument.findUnique({
      where: { id: documentId },
      include: { tripDay: true, tripPlace: true },
    });
  }

  countDocumentsByTrip(tripId: string) {
    return this.prisma.travelDocument.count({ where: { tripId } });
  }

  findTripDay(dayId: string) {
    return this.prisma.tripDay.findUnique({ where: { id: dayId } });
  }

  findTripPlace(placeId: string) {
    return this.prisma.tripPlace.findUnique({ where: { id: placeId } });
  }

  createDocument(data: Prisma.TravelDocumentCreateInput) {
    return this.prisma.travelDocument.create({
      data,
      include: { tripDay: true, tripPlace: true },
    });
  }

  updateDocument(documentId: string, data: Prisma.TravelDocumentUpdateInput) {
    return this.prisma.travelDocument.update({
      where: { id: documentId },
      data,
      include: { tripDay: true, tripPlace: true },
    });
  }

  deleteDocument(documentId: string) {
    return this.prisma.travelDocument.delete({ where: { id: documentId } });
  }

  findUpcomingDocumentsForTrips(tripIds: string[], from: Date, to: Date) {
    return this.prisma.travelDocument.findMany({
      where: {
        tripId: { in: tripIds },
        isReminder: true,
        expiredAt: { gte: from, lte: to },
      },
      include: { trip: true },
      orderBy: { expiredAt: 'asc' },
    });
  }
}
