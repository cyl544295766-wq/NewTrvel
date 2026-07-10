import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { Trip, TripMemberRole, TripStatus } from '@prisma/client';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { TripMembersService } from '../trip-members/trip-members.service';
import { editableTripRoles } from '../trip-members/types/trip-permission.type';
import { CreateTripDto } from './dto/create-trip.dto';
import { UpdateTripDto } from './dto/update-trip.dto';
import { TripResponse } from './types/trip-response.type';
import { TripsRepository } from './trips.repository';

@Injectable()
export class TripsService {
  constructor(
    private readonly tripsRepository: TripsRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async create(createTripDto: CreateTripDto, currentUser: AuthenticatedUser) {
    this.validateDateRange(createTripDto.startDate, createTripDto.endDate);

    const trip = await this.tripsRepository.createWithOwner(
      {
        title: createTripDto.title.trim(),
        description: this.toNullableString(createTripDto.description),
        destination: this.toNullableString(createTripDto.destination),
        startDate: this.toNullableDate(createTripDto.startDate),
        endDate: this.toNullableDate(createTripDto.endDate),
        coverImageUrl: this.toNullableString(createTripDto.coverImageUrl),
        owner: { connect: { id: currentUser.id } },
      },
      currentUser.id,
    );

    return { trip: this.toTripResponse(trip, TripMemberRole.owner) };
  }

  async findAll(currentUser: AuthenticatedUser) {
    const memberships = await this.tripsRepository.findTripsForUser(currentUser.id);

    return {
      trips: memberships.map((membership) => this.toTripResponse(membership.trip, membership.role)),
    };
  }

  async findOne(tripId: string, currentUser: AuthenticatedUser) {
    const membership = await this.tripMembersService.requireTripMember(tripId, currentUser.id);
    const trip = await this.tripsRepository.findById(tripId);

    if (!trip) {
      throw new NotFoundException('Trip not found');
    }

    return { trip: this.toTripResponse(trip, membership.role) };
  }

  async update(tripId: string, updateTripDto: UpdateTripDto, currentUser: AuthenticatedUser) {
    this.validateDateRange(updateTripDto.startDate, updateTripDto.endDate);
    const membership = await this.verifyCanManageTrip(tripId, currentUser.id);
    const existingTrip = await this.tripsRepository.findById(tripId);

    if (!existingTrip) {
      throw new NotFoundException('Trip not found');
    }

    const trip = await this.tripsRepository.update(tripId, {
      title: updateTripDto.title?.trim(),
      description: this.toOptionalNullableString(updateTripDto.description),
      destination: this.toOptionalNullableString(updateTripDto.destination),
      startDate: this.toOptionalNullableDate(updateTripDto.startDate),
      endDate: this.toOptionalNullableDate(updateTripDto.endDate),
      status: updateTripDto.status,
      coverImageUrl: this.toOptionalNullableString(updateTripDto.coverImageUrl),
    });

    return { trip: this.toTripResponse(trip, membership.role) };
  }

  async archive(tripId: string, currentUser: AuthenticatedUser) {
    const membership = await this.verifyCanManageTrip(tripId, currentUser.id);
    const existingTrip = await this.tripsRepository.findById(tripId);

    if (!existingTrip) {
      throw new NotFoundException('Trip not found');
    }

    const trip = await this.tripsRepository.update(tripId, {
      status: TripStatus.archived,
      archivedAt: new Date(),
    });

    return { trip: this.toTripResponse(trip, membership.role) };
  }

  async remove(tripId: string, currentUser: AuthenticatedUser) {
    const membership = await this.verifyCanManageTrip(tripId, currentUser.id);
    const existingTrip = await this.tripsRepository.findById(tripId);

    if (!existingTrip) {
      throw new NotFoundException('Trip not found');
    }

    const trip = await this.tripsRepository.softDelete(tripId);

    return { trip: this.toTripResponse(trip, membership.role) };
  }

  async duplicate(tripId: string, currentUser: AuthenticatedUser) {
    await this.verifyCanManageTrip(tripId, currentUser.id);
    const sourceTrip = await this.tripsRepository.findByIdWithDaysAndPlaces(tripId);

    if (!sourceTrip) {
      throw new NotFoundException('Trip not found');
    }

    const trip = await this.tripsRepository.duplicateWithStructure(tripId, currentUser.id);

    if (!trip) {
      throw new NotFoundException('Trip not found');
    }

    return { trip: this.toTripResponse(trip, TripMemberRole.owner) };
  }

  async favorite(tripId: string, currentUser: AuthenticatedUser) {
    const membership = await this.tripMembersService.requireTripMember(tripId, currentUser.id);
    const existingTrip = await this.tripsRepository.findById(tripId);

    if (!existingTrip) {
      throw new NotFoundException('Trip not found');
    }

    const trip = await this.tripsRepository.update(tripId, {
      isFavorite: !existingTrip.isFavorite,
    });

    return { trip: this.toTripResponse(trip, membership.role) };
  }

  private verifyCanManageTrip(tripId: string, userId: string) {
    return this.tripMembersService.requireTripRole(tripId, userId, editableTripRoles);
  }

  private toTripResponse(trip: Trip, currentUserRole: TripMemberRole): TripResponse {
    return {
      id: trip.id,
      title: trip.title,
      description: trip.description,
      destination: trip.destination,
      startDate: trip.startDate,
      endDate: trip.endDate,
      status: trip.status,
      coverImageUrl: trip.coverImageUrl,
      ownerId: trip.ownerId,
      currentUserRole,
      isFavorite: trip.isFavorite,
      archivedAt: trip.archivedAt,
      deletedAt: trip.deletedAt,
      createdAt: trip.createdAt,
      updatedAt: trip.updatedAt,
    };
  }

  private validateDateRange(startDate?: string, endDate?: string) {
    if (!startDate || !endDate) {
      return;
    }

    if (new Date(endDate) < new Date(startDate)) {
      throw new BadRequestException('endDate must be greater than or equal to startDate');
    }
  }

  private toNullableDate(value?: string): Date | null {
    return value ? new Date(value) : null;
  }

  private toOptionalNullableDate(value?: string): Date | null | undefined {
    return value === undefined ? undefined : this.toNullableDate(value);
  }

  private toNullableString(value?: string): string | null {
    const trimmed = value?.trim();

    return trimmed ? trimmed : null;
  }

  private toOptionalNullableString(value?: string): string | null | undefined {
    return value === undefined ? undefined : this.toNullableString(value);
  }
}
