import { Injectable } from '@nestjs/common';
import { TripMemberRole } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TripMembersRepository {
  constructor(private readonly prisma: PrismaService) {}

  findMembership(tripId: string, userId: string) {
    return this.prisma.tripMember.findUnique({
      where: {
        tripId_userId: {
          tripId,
          userId,
        },
      },
    });
  }

  createOwner(tripId: string, userId: string) {
    return this.prisma.tripMember.create({
      data: {
        tripId,
        userId,
        role: TripMemberRole.owner,
      },
    });
  }

  findMembers(tripId: string) {
    return this.prisma.tripMember.findMany({
      where: { tripId },
      include: { user: true },
      orderBy: { createdAt: 'asc' },
    });
  }
}
