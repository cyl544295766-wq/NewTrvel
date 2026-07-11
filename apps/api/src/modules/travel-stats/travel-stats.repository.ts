import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

export type TravelStatsTrip = Prisma.TripGetPayload<{
  include: {
    days: { select: { id: true; date: true } };
    expenses: true;
  };
}>;

@Injectable()
export class TravelStatsRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findTripsForUser(userId: string): Promise<TravelStatsTrip[]> {
    const memberships = await this.prisma.tripMember.findMany({
      where: { userId, trip: { deletedAt: null } },
      include: {
        trip: {
          include: {
            days: { select: { id: true, date: true }, orderBy: { date: 'asc' } },
            expenses: true,
          },
        },
      },
      orderBy: { trip: { startDate: 'asc' } },
    });
    return memberships.map((membership) => membership.trip);
  }
}
