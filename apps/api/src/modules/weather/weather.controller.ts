import { Controller, Get, Param, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { WeatherService } from './weather.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/weather')
export class WeatherController {
  constructor(private readonly weatherService: WeatherService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.weatherService.getTripWeather(tripId, user.id);
  }

  @Get(':tripDayId')
  findOne(
    @Param('tripId') tripId: string,
    @Param('tripDayId') tripDayId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.weatherService.getTripDayWeather(tripId, tripDayId, user.id);
  }
}
