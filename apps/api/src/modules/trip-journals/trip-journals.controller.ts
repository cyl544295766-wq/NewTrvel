import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreateTripJournalDto } from './dto/create-trip-journal.dto';
import { ReorderJournalPhotosDto } from './dto/reorder-journal-photos.dto';
import { UpdateTripJournalDto } from './dto/update-trip-journal.dto';
import { TripJournalsService } from './trip-journals.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/journals')
export class TripJournalsController {
  constructor(private readonly tripJournalsService: TripJournalsService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripJournalsService.findAll(tripId, user.id);
  }

  @Get(':journalId')
  findOne(
    @Param('tripId') tripId: string,
    @Param('journalId') journalId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.tripJournalsService.findOne(tripId, journalId, user.id);
  }

  @Post()
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateTripJournalDto,
  ) {
    return this.tripJournalsService.create(tripId, user.id, dto);
  }

  @Patch(':journalId')
  update(
    @Param('tripId') tripId: string,
    @Param('journalId') journalId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTripJournalDto,
  ) {
    return this.tripJournalsService.update(tripId, journalId, user.id, dto);
  }

  @Delete(':journalId')
  delete(
    @Param('tripId') tripId: string,
    @Param('journalId') journalId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.tripJournalsService.delete(tripId, journalId, user.id);
  }

  @Patch(':journalId/photos/reorder')
  reorderPhotos(
    @Param('tripId') tripId: string,
    @Param('journalId') journalId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: ReorderJournalPhotosDto,
  ) {
    return this.tripJournalsService.reorderPhotos(tripId, journalId, user.id, dto.photoIds);
  }
}
