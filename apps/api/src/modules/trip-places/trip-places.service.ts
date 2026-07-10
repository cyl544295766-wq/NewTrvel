import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { TripMemberRole, TripPlaceType } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreateTripPlaceDto } from './dto/create-trip-place.dto';
import { ReorderTripPlacesDto } from './dto/reorder-trip-places.dto';
import { UpdateTripPlaceDto } from './dto/update-trip-place.dto';
import { TripPlacesRepository } from './trip-places.repository';

const editRoles = [TripMemberRole.owner, TripMemberRole.admin, TripMemberRole.member];

@Injectable()
export class TripPlacesService {
  constructor(
    private readonly tripPlacesRepository: TripPlacesRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async create(tripId: string, userId: string, dto: CreateTripPlaceDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    if (dto.tripDayId) await this.ensureDayBelongsToTrip(dto.tripDayId, tripId);
    const sortOrder = dto.tripDayId
      ? await this.tripPlacesRepository.countPlacesInDay(dto.tripDayId)
      : 0;

    return {
      place: await this.tripPlacesRepository.create({
        trip: { connect: { id: tripId } },
        tripDay: dto.tripDayId ? { connect: { id: dto.tripDayId } } : undefined,
        name: dto.name.trim(),
        type: dto.type ?? TripPlaceType.custom,
        address: this.toNullable(dto.address),
        latitude: dto.latitude,
        longitude: dto.longitude,
        startTime: dto.startTime ? new Date(dto.startTime) : null,
        endTime: dto.endTime ? new Date(dto.endTime) : null,
        notes: this.toNullable(dto.notes),
        isCompleted: dto.isCompleted ?? false,
        sortOrder,
      }),
    };
  }

  async update(tripId: string, placeId: string, userId: string, dto: UpdateTripPlaceDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const place = await this.tripPlacesRepository.findPlace(placeId);
    if (!place || place.tripId !== tripId) throw new NotFoundException('地点不存在');
    if (dto.tripDayId) await this.ensureDayBelongsToTrip(dto.tripDayId, tripId);

    return {
      place: await this.tripPlacesRepository.update(placeId, {
        tripDay:
          dto.tripDayId === undefined
            ? undefined
            : dto.tripDayId
              ? { connect: { id: dto.tripDayId } }
              : { disconnect: true },
        name: dto.name?.trim(),
        type: dto.type,
        address: dto.address === undefined ? undefined : this.toNullable(dto.address),
        latitude: dto.latitude,
        longitude: dto.longitude,
        startTime: dto.startTime === undefined ? undefined : new Date(dto.startTime),
        endTime: dto.endTime === undefined ? undefined : new Date(dto.endTime),
        notes: dto.notes === undefined ? undefined : this.toNullable(dto.notes),
        sortOrder: dto.sortOrder,
        isCompleted: dto.isCompleted,
      }),
    };
  }

  async move(tripId: string, placeId: string, userId: string, tripDayId: string) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const place = await this.tripPlacesRepository.findPlace(placeId);
    if (!place || place.tripId !== tripId) throw new NotFoundException('地点不存在');
    await this.ensureDayBelongsToTrip(tripDayId, tripId);
    const sortOrder = await this.tripPlacesRepository.countPlacesInDay(tripDayId);

    return { place: await this.tripPlacesRepository.moveToDay(placeId, tripDayId, sortOrder) };
  }

  async delete(tripId: string, placeId: string, userId: string) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    const place = await this.tripPlacesRepository.findPlace(placeId);
    if (!place || place.tripId !== tripId) throw new NotFoundException('地点不存在');
    await this.tripPlacesRepository.delete(placeId);
    return { success: true };
  }

  async reorder(tripId: string, dayId: string, userId: string, dto: ReorderTripPlacesDto) {
    await this.tripMembersService.requireTripRole(tripId, userId, editRoles);
    await this.ensureDayBelongsToTrip(dayId, tripId);
    const places = await this.tripPlacesRepository.findPlacesByIds(dto.placeIds);
    if (places.length !== dto.placeIds.length) throw new BadRequestException('地点列表不完整');
    if (places.some((place) => place.tripId !== tripId || place.tripDayId !== dayId)) {
      throw new BadRequestException('地点必须属于当前旅行和当前日期');
    }

    return { places: await this.tripPlacesRepository.reorder(dto.placeIds) };
  }

  private async ensureDayBelongsToTrip(dayId: string, tripId: string) {
    const day = await this.tripPlacesRepository.findDay(dayId);
    if (!day || day.tripId !== tripId) throw new BadRequestException('行程日期不属于当前旅行');
  }

  private toNullable(value?: string) {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }
}
