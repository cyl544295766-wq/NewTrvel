import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreateTravelDocumentDto } from './dto/create-travel-document.dto';
import { UpdateTravelDocumentDto } from './dto/update-travel-document.dto';
import { TravelDocumentsService } from './travel-documents.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/documents')
export class TravelDocumentsController {
  constructor(private readonly travelDocumentsService: TravelDocumentsService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.travelDocumentsService.findAll(tripId, user.id);
  }

  @Get(':documentId')
  findOne(
    @Param('tripId') tripId: string,
    @Param('documentId') documentId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.travelDocumentsService.findOne(tripId, documentId, user.id);
  }

  @Post()
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateTravelDocumentDto,
  ) {
    return this.travelDocumentsService.create(tripId, user.id, dto);
  }

  @Patch(':documentId')
  update(
    @Param('tripId') tripId: string,
    @Param('documentId') documentId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTravelDocumentDto,
  ) {
    return this.travelDocumentsService.update(tripId, documentId, user.id, dto);
  }

  @Delete(':documentId')
  delete(
    @Param('tripId') tripId: string,
    @Param('documentId') documentId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.travelDocumentsService.delete(tripId, documentId, user.id);
  }
}
