import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreatePhotoDto } from './dto/create-photo.dto';
import { UpdatePhotoDto } from './dto/update-photo.dto';
import { PhotosService } from './photos.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/photos')
export class PhotosController {
  constructor(private readonly photosService: PhotosService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.photosService.findAll(tripId, user.id);
  }

  @Get(':photoId')
  findOne(
    @Param('tripId') tripId: string,
    @Param('photoId') photoId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.photosService.findOne(tripId, photoId, user.id);
  }

  @Post()
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreatePhotoDto,
  ) {
    return this.photosService.create(tripId, user.id, dto);
  }

  @Patch(':photoId')
  update(
    @Param('tripId') tripId: string,
    @Param('photoId') photoId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdatePhotoDto,
  ) {
    return this.photosService.update(tripId, photoId, user.id, dto);
  }

  @Delete(':photoId')
  delete(
    @Param('tripId') tripId: string,
    @Param('photoId') photoId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.photosService.delete(tripId, photoId, user.id);
  }
}
