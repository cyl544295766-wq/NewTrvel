import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { TripMemberRole } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { UpdateTripDayDto } from './dto/update-trip-day.dto';
import { TripDaysRepository } from './trip-days.repository';

const editRoles = [TripMemberRole.owner, TripMemberRole.admin, TripMemberRole.member];

@Injectable()
export class TripDaysService {
  constructor(
    private readonly tripDaysRepository: TripDaysRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async generate(tripId: string, userId: string) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const trip = await this.tripDaysRepository.findTrip(tripId);

    if (!trip) throw new NotFoundException('旅行不存在');
    if (!trip.startDate || !trip.endDate)
      throw new BadRequestException('旅行缺少开始日期或结束日期');

    const dates = this.buildDateRange(trip.startDate, trip.endDate);
    if (dates.length > 60) throw new BadRequestException('行程天数不能超过 60 天');

    await this.tripDaysRepository.createMany(
      dates.map((date, index) => ({ tripId, date, dayIndex: index + 1 })),
    );

    return this.findAll(tripId, userId);
  }

  async findAll(tripId: string, userId: string) {
    await this.tripMembersService.requireTripMember(tripId, userId);
    return { days: await this.tripDaysRepository.findAllByTrip(tripId) };
  }

  async update(tripId: string, dayId: string, userId: string, dto: UpdateTripDayDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const day = await this.tripDaysRepository.findById(dayId);
    if (!day || day.tripId !== tripId) throw new NotFoundException('行程天数不存在');

    return {
      day: await this.tripDaysRepository.update(dayId, {
        title: dto.title === undefined ? undefined : this.toNullable(dto.title),
        summary: dto.summary === undefined ? undefined : this.toNullable(dto.summary),
      }),
    };
  }

  private buildDateRange(startDate: Date, endDate: Date) {
    const start = this.toUtcDate(startDate);
    const end = this.toUtcDate(endDate);
    if (end < start) throw new BadRequestException('结束日期不能早于开始日期');

    const dates: Date[] = [];
    const cursor = new Date(start);
    while (cursor <= end) {
      dates.push(new Date(cursor));
      cursor.setUTCDate(cursor.getUTCDate() + 1);
    }
    return dates;
  }

  private toUtcDate(value: Date) {
    return new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth(), value.getUTCDate()));
  }

  private toNullable(value: string) {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }
}
