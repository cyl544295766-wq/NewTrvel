import { ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { TripMemberRole } from '@prisma/client';
import { TripMembersRepository } from './trip-members.repository';

@Injectable()
export class TripMembersService {
  constructor(private readonly tripMembersRepository: TripMembersRepository) {}

  createOwner(tripId: string, userId: string) {
    return this.tripMembersRepository.createOwner(tripId, userId);
  }

  async requireTripMember(tripId: string, userId: string) {
    const membership = await this.tripMembersRepository.findMembership(tripId, userId);

    if (!membership) {
      throw new NotFoundException('Trip not found');
    }

    return membership;
  }

  async requireTripRole(tripId: string, userId: string, roles: TripMemberRole[]) {
    const membership = await this.requireTripMember(tripId, userId);

    if (!roles.includes(membership.role)) {
      throw new ForbiddenException('Insufficient trip permission');
    }

    return membership;
  }

  async findMembers(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const members = await this.tripMembersRepository.findMembers(tripId);

    return {
      members: members.map((member) => ({
        userId: member.userId,
        username: member.user.username,
        displayName: member.user.displayName,
        role: member.role,
      })),
    };
  }
}
