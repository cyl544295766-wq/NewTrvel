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

  findMemberById(memberId: string) {
    return this.prisma.tripMember.findUnique({
      where: { id: memberId },
      include: { user: true },
    });
  }

  findMemberByUserId(tripId: string, userId: string) {
    return this.findMembership(tripId, userId);
  }

  findTripSummary(tripId: string) {
    return this.prisma.trip.findUnique({ where: { id: tripId }, select: { id: true, title: true } });
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

  createMember(tripId: string, userId: string, role: TripMemberRole) {
    return this.prisma.tripMember.create({
      data: {
        tripId,
        userId,
        role,
      },
    });
  }

  updateRole(memberId: string, role: TripMemberRole) {
    return this.prisma.tripMember.update({
      where: { id: memberId },
      data: { role },
    });
  }

  deleteMember(memberId: string) {
    return this.prisma.tripMember.delete({ where: { id: memberId } });
  }
}
