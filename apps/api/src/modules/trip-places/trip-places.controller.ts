import { Body, Controller, Delete, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreateTripPlaceDto } from './dto/create-trip-place.dto';
import { ReorderTripPlacesDto } from './dto/reorder-trip-places.dto';
import { UpdateTripPlaceDto } from './dto/update-trip-place.dto';
import { TripPlacesService } from './trip-places.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId')
export class TripPlacesController {
  constructor(private readonly tripPlacesService: TripPlacesService) {}

  @Post('places')
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateTripPlaceDto,
  ) {
    return this.tripPlacesService.create(tripId, user.id, dto);
  }

  @Patch('places/:placeId')
  update(
    @Param('tripId') tripId: string,
    @Param('placeId') placeId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTripPlaceDto,
  ) {
    return this.tripPlacesService.update(tripId, placeId, user.id, dto);
  }

  @Delete('places/:placeId')
  delete(
    @Param('tripId') tripId: string,
    @Param('placeId') placeId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.tripPlacesService.delete(tripId, placeId, user.id);
  }

  @Patch('days/:dayId/places/reorder')
  reorder(
    @Param('tripId') tripId: string,
    @Param('dayId') dayId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: ReorderTripPlacesDto,
  ) {
    return this.tripPlacesService.reorder(tripId, dayId, user.id, dto);
  }
}
