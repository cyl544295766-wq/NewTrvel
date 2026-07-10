import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreateTripDto } from './dto/create-trip.dto';
import { UpdateTripDto } from './dto/update-trip.dto';
import { TripsService } from './trips.service';

@UseGuards(JwtAuthGuard)
@Controller('trips')
export class TripsController {
  constructor(private readonly tripsService: TripsService) {}

  @Post()
  create(@Body() createTripDto: CreateTripDto, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.create(createTripDto, currentUser);
  }

  @Get()
  findAll(@CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.findAll(currentUser);
  }

  @Get(':tripId')
  findOne(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.findOne(tripId, currentUser);
  }

  @Get(':tripId/route')
  getRoute(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.getRoute(tripId, currentUser);
  }

  @Patch(':tripId')
  update(
    @Param('tripId') tripId: string,
    @Body() updateTripDto: UpdateTripDto,
    @CurrentUser() currentUser: AuthenticatedUser,
  ) {
    return this.tripsService.update(tripId, updateTripDto, currentUser);
  }

  @Patch(':tripId/archive')
  archive(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.archive(tripId, currentUser);
  }

  @Post(':tripId/duplicate')
  duplicate(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.duplicate(tripId, currentUser);
  }

  @Patch(':tripId/favorite')
  favorite(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.favorite(tripId, currentUser);
  }

  @Delete(':tripId')
  remove(@Param('tripId') tripId: string, @CurrentUser() currentUser: AuthenticatedUser) {
    return this.tripsService.remove(tripId, currentUser);
  }
}
