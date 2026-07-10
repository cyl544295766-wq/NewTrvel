import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { TripMemberRole, UserStatus } from '@prisma/client';
import { NotificationsService } from '../notifications/notifications.service';
import { UsersService } from '../users/users.service';
import { InviteTripMemberDto } from './dto/invite-trip-member.dto';
import { TripMembersRepository } from './trip-members.repository';
import { editableTripRoles } from './types/trip-permission.type';

@Injectable()
export class TripMembersService {
  constructor(
    private readonly tripMembersRepository: TripMembersRepository,
    private readonly usersService: UsersService,
    private readonly notificationsService: NotificationsService,
  ) {}

  createOwner(tripId: string, userId: string) {
    return this.tripMembersRepository.createOwner(tripId, userId);
  }

  async requireTripMember(tripId: string, userId: string) {
    const membership = await this.tripMembersRepository.findMembership(tripId, userId);

    if (!membership) {
      throw new NotFoundException('旅行不存在');
    }

    return membership;
  }

  async requireTripRole(tripId: string, userId: string, roles: TripMemberRole[]) {
    const membership = await this.requireTripMember(tripId, userId);

    if (!roles.includes(membership.role)) {
      throw new ForbiddenException('没有足够的旅行权限');
    }

    return membership;
  }

  async findMembers(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const members = await this.tripMembersRepository.findMembers(tripId);

    return {
      members: members.map((member) => ({
        userId: member.userId,
        id: member.id,
        username: member.user.username,
        displayName: member.user.displayName,
        role: member.role,
      })),
    };
  }

  async invite(tripId: string, inviterUserId: string, dto: InviteTripMemberDto) {
    await this.requireTripRole(tripId, inviterUserId, editableTripRoles);
    const role = dto.role ?? TripMemberRole.member;
    if (role === TripMemberRole.owner) {
      throw new BadRequestException('不能邀请所有者');
    }

    const user = await this.usersService.findByUsername(dto.username.trim());
    if (!user || user.status !== UserStatus.active) {
      throw new NotFoundException('用户不存在');
    }

    const existingMember = await this.tripMembersRepository.findMemberByUserId(tripId, user.id);
    if (existingMember) {
      throw new BadRequestException('该用户已加入旅行');
    }

    await this.tripMembersRepository.createMember(tripId, user.id, role);
    const trip = await this.tripMembersRepository.findTripSummary(tripId);
    if (trip) {
      await this.notificationsService.createMemberInvited(user.id, tripId, trip.title, role);
    }
    return this.toMembersResponse(tripId);
  }

  async updateRole(tripId: string, memberId: string, role: TripMemberRole, operatorUserId: string) {
    await this.requireTripRole(tripId, operatorUserId, editableTripRoles);
    const targetMember = await this.requireTargetMember(tripId, memberId);
    if (targetMember.role === TripMemberRole.owner) {
      throw new BadRequestException('不能修改所有者角色');
    }
    if (role === TripMemberRole.owner) {
      throw new BadRequestException('不能将成员改为所有者');
    }

    await this.tripMembersRepository.updateRole(memberId, role);
    const trip = await this.tripMembersRepository.findTripSummary(tripId);
    if (trip) {
      await this.notificationsService.createRoleChanged(
        targetMember.userId,
        tripId,
        trip.title,
        role,
      );
    }
    return this.toMembersResponse(tripId);
  }

  async remove(tripId: string, memberId: string, operatorUserId: string) {
    await this.requireTripRole(tripId, operatorUserId, editableTripRoles);
    const targetMember = await this.requireTargetMember(tripId, memberId);
    if (targetMember.role === TripMemberRole.owner) {
      throw new BadRequestException('不能移除所有者');
    }

    await this.tripMembersRepository.deleteMember(memberId);
    return this.toMembersResponse(tripId);
  }

  private async requireTargetMember(tripId: string, memberId: string) {
    const member = await this.tripMembersRepository.findMemberById(memberId);
    if (!member || member.tripId !== tripId) {
      throw new NotFoundException('成员不存在');
    }

    return member;
  }

  private async toMembersResponse(tripId: string) {
    const members = await this.tripMembersRepository.findMembers(tripId);
    return {
      members: members.map((member) => ({
        id: member.id,
        userId: member.userId,
        username: member.user.username,
        displayName: member.user.displayName,
        role: member.role,
      })),
    };
  }
}
