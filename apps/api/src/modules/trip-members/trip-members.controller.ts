import { Controller, Get, Param, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { TripMembersService } from './trip-members.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/members')
export class TripMembersController {
  constructor(private readonly tripMembersService: TripMembersService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripMembersService.findMembers(tripId, user.id);
  }
}
