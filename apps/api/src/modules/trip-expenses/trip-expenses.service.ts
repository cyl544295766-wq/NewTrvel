import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { TripExpenseCategory, TripMemberRole } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreateTripExpenseDto } from './dto/create-trip-expense.dto';
import { UpdateTripExpenseDto } from './dto/update-trip-expense.dto';
import { TripExpensesRepository } from './trip-expenses.repository';

const editRoles = [TripMemberRole.owner, TripMemberRole.admin, TripMemberRole.member];

type MemberInfo = { userId: string; displayName: string; username: string };

@Injectable()
export class TripExpensesService {
  constructor(
    private readonly tripExpensesRepository: TripExpensesRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async findAll(tripId: string, userId: string) {
    await this.tripMembersService.requireTripMember(tripId, userId);
    const expenses = await this.tripExpensesRepository.findExpenses(tripId);
    const members = await this.getMembers(tripId);

    return {
      expenses: expenses.map((expense) => ({
        id: expense.id,
        title: expense.title,
        amount: expense.amount.toFixed(2),
        currency: expense.currency,
        category: expense.category,
        spentAt: expense.spentAt,
        notes: expense.notes,
        payer: { id: expense.payer.id, displayName: expense.payer.displayName },
        shares: expense.shares.map((share) => ({
          userId: share.userId,
          displayName: share.user.displayName,
          shareAmount: share.shareAmount.toFixed(2),
        })),
      })),
      summary: this.buildSummary(expenses, members),
    };
  }

  async create(tripId: string, userId: string, dto: CreateTripExpenseDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    await this.validateExpenseMembers(tripId, dto.payerUserId, dto.shareUserIds);
    const amountCents = this.toCents(dto.amount);
    const shares = this.calculateShares(amountCents, dto.shareUserIds);

    return {
      expense: await this.tripExpensesRepository.create({
        trip: { connect: { id: tripId } },
        payer: { connect: { id: dto.payerUserId } },
        title: dto.title.trim(),
        amount: (amountCents / 100).toFixed(2),
        currency: dto.currency?.trim() || 'CNY',
        category: dto.category ?? TripExpenseCategory.other,
        spentAt: new Date(dto.spentAt),
        notes: this.toNullable(dto.notes),
        shares: { create: shares },
      }),
    };
  }

  async update(tripId: string, expenseId: string, userId: string, dto: UpdateTripExpenseDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const expense = await this.tripExpensesRepository.findExpense(expenseId);
    if (!expense || expense.tripId !== tripId) throw new NotFoundException('费用不存在');

    const payerUserId = dto.payerUserId ?? expense.payerUserId;
    const shareUserIds =
      dto.shareUserIds ??
      (await this.tripExpensesRepository.findExpenses(tripId))
        .find((item) => item.id === expenseId)
        ?.shares.map((share) => share.userId) ??
      [];
    await this.validateExpenseMembers(tripId, payerUserId, shareUserIds);
    const amountCents =
      dto.amount === undefined
        ? Math.round(Number(expense.amount) * 100)
        : this.toCents(dto.amount);
    const shares = this.calculateShares(amountCents, shareUserIds);

    return {
      expense: await this.tripExpensesRepository.update(
        expenseId,
        {
          title: dto.title?.trim(),
          amount: dto.amount === undefined ? undefined : (amountCents / 100).toFixed(2),
          currency: dto.currency?.trim(),
          category: dto.category,
          spentAt: dto.spentAt === undefined ? undefined : new Date(dto.spentAt),
          notes: dto.notes === undefined ? undefined : this.toNullable(dto.notes),
          payer: dto.payerUserId === undefined ? undefined : { connect: { id: payerUserId } },
        },
        shares,
      ),
    };
  }

  async delete(tripId: string, expenseId: string, userId: string) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const expense = await this.tripExpensesRepository.findExpense(expenseId);
    if (!expense || expense.tripId !== tripId) throw new NotFoundException('费用不存在');
    await this.tripExpensesRepository.delete(expenseId);
    return { success: true };
  }

  private async validateExpenseMembers(
    tripId: string,
    payerUserId: string,
    shareUserIds: string[],
  ) {
    if (shareUserIds.length === 0) throw new BadRequestException('分摊成员不能为空');
    const members = await this.tripExpensesRepository.findTripMembers(tripId);
    const memberIds = new Set(members.map((member) => member.userId));
    if (!memberIds.has(payerUserId)) throw new BadRequestException('付款人必须是旅行成员');
    if (shareUserIds.some((id) => !memberIds.has(id)))
      throw new BadRequestException('分摊成员必须全部属于当前旅行');
  }

  private calculateShares(amountCents: number, userIds: string[]) {
    const uniqueUserIds = [...new Set(userIds)];
    const base = Math.floor(amountCents / uniqueUserIds.length);
    let allocated = 0;
    return uniqueUserIds.map((userId, index) => {
      const cents = index === uniqueUserIds.length - 1 ? amountCents - allocated : base;
      allocated += cents;
      return { userId, shareAmount: (cents / 100).toFixed(2) };
    });
  }

  private buildSummary(
    expenses: Awaited<ReturnType<TripExpensesRepository['findExpenses']>>,
    members: MemberInfo[],
  ) {
    const paid = new Map<string, number>();
    const share = new Map<string, number>();
    let total = 0;
    for (const expense of expenses) {
      const cents = Math.round(Number(expense.amount) * 100);
      total += cents;
      paid.set(expense.payerUserId, (paid.get(expense.payerUserId) ?? 0) + cents);
      for (const expenseShare of expense.shares) {
        share.set(
          expenseShare.userId,
          (share.get(expenseShare.userId) ?? 0) +
            Math.round(Number(expenseShare.shareAmount) * 100),
        );
      }
    }
    const toRows = (source: Map<string, number>) =>
      members.map((member) => ({
        userId: member.userId,
        displayName: member.displayName,
        amount: ((source.get(member.userId) ?? 0) / 100).toFixed(2),
      }));
    return {
      currency: expenses[0]?.currency ?? 'CNY',
      totalAmount: (total / 100).toFixed(2),
      paidByUser: toRows(paid),
      shareByUser: toRows(share),
      balanceByUser: members.map((member) => ({
        userId: member.userId,
        displayName: member.displayName,
        amount: (((paid.get(member.userId) ?? 0) - (share.get(member.userId) ?? 0)) / 100).toFixed(
          2,
        ),
      })),
    };
  }

  private async getMembers(tripId: string): Promise<MemberInfo[]> {
    const members = await this.tripExpensesRepository.findTripMembers(tripId);
    return members.map((member) => ({
      userId: member.userId,
      displayName: member.user.displayName,
      username: member.user.username,
    }));
  }

  private toCents(amount: string) {
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) throw new BadRequestException('金额必须大于 0');
    return Math.round(value * 100);
  }

  private toNullable(value?: string) {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }
}
