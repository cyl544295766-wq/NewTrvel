import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { PhotosController } from './photos.controller';
import { PhotosRepository } from './photos.repository';
import { PhotosService } from './photos.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [PhotosController],
  providers: [PhotosRepository, PhotosService],
  exports: [PhotosRepository],
})
export class PhotosModule {}
