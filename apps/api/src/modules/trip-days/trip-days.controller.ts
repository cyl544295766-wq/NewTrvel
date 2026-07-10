import { Body, Controller, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { UpdateTripDayDto } from './dto/update-trip-day.dto';
import { TripDaysService } from './trip-days.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/days')
export class TripDaysController {
  constructor(private readonly tripDaysService: TripDaysService) {}

  @Post('generate')
  generate(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripDaysService.generate(tripId, user.id);
  }

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripDaysService.findAll(tripId, user.id);
  }

  @Patch(':dayId')
  update(
    @Param('tripId') tripId: string,
    @Param('dayId') dayId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTripDayDto,
  ) {
    return this.tripDaysService.update(tripId, dayId, user.id, dto);
  }
}
