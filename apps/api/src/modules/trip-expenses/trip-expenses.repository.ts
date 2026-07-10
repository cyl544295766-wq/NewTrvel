import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../../database/prisma.service';

@Injectable()
export class TripExpensesRepository {
  constructor(private readonly prisma: PrismaService) {}

  findTripMembers(tripId: string) {
    return this.prisma.tripMember.findMany({ where: { tripId }, include: { user: true } });
  }

  findExpenses(tripId: string) {
    return this.prisma.tripExpense.findMany({
      where: { tripId },
      include: { payer: true, shares: { include: { user: true } } },
      orderBy: { spentAt: 'desc' },
    });
  }

  findSummaryExpenses(tripId: string) {
    return this.findExpenses(tripId);
  }

  findTripBudget(tripId: string) {
    return this.prisma.trip.findUnique({ where: { id: tripId }, select: { budget: true } });
  }

  findExpensesForTrips(tripIds: string[]) {
    return this.prisma.tripExpense.findMany({
      where: { tripId: { in: tripIds } },
      include: { payer: true, trip: true },
      orderBy: { createdAt: 'desc' },
    });
  }

  findRecentExpensesForTrips(tripIds: string[], take: number) {
    return this.prisma.tripExpense.findMany({
      where: { tripId: { in: tripIds } },
      include: { payer: true, trip: true },
      orderBy: { createdAt: 'desc' },
      take,
    });
  }

  findExpense(expenseId: string) {
    return this.prisma.tripExpense.findUnique({ where: { id: expenseId } });
  }

  create(data: Prisma.TripExpenseCreateInput) {
    return this.prisma.tripExpense.create({
      data,
      include: { payer: true, shares: { include: { user: true } } },
    });
  }

  async update(
    expenseId: string,
    data: Prisma.TripExpenseUpdateInput,
    shares: { userId: string; shareAmount: string }[],
  ) {
    return this.prisma.$transaction(async (tx) => {
      await tx.tripExpenseShare.deleteMany({ where: { expenseId } });
      return tx.tripExpense.update({
        where: { id: expenseId },
        data: {
          ...data,
          shares: { create: shares },
        },
        include: { payer: true, shares: { include: { user: true } } },
      });
    });
  }

  delete(expenseId: string) {
    return this.prisma.tripExpense.delete({ where: { id: expenseId } });
  }
}
