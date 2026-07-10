import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { InviteTripMemberDto } from './dto/invite-trip-member.dto';
import { UpdateTripMemberRoleDto } from './dto/update-trip-member-role.dto';
import { TripMembersService } from './trip-members.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/members')
export class TripMembersController {
  constructor(private readonly tripMembersService: TripMembersService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripMembersService.findMembers(tripId, user.id);
  }

  @Post()
  invite(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: InviteTripMemberDto,
  ) {
    return this.tripMembersService.invite(tripId, user.id, dto);
  }

  @Patch(':memberId/role')
  updateRole(
    @Param('tripId') tripId: string,
    @Param('memberId') memberId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTripMemberRoleDto,
  ) {
    return this.tripMembersService.updateRole(tripId, memberId, dto.role, user.id);
  }

  @Delete(':memberId')
  remove(
    @Param('tripId') tripId: string,
    @Param('memberId') memberId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.tripMembersService.remove(tripId, memberId, user.id);
  }
}
