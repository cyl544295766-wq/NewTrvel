import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripPlacesController } from './trip-places.controller';
import { TripPlacesRepository } from './trip-places.repository';
import { TripPlacesService } from './trip-places.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripPlacesController],
  providers: [TripPlacesRepository, TripPlacesService],
})
export class TripPlacesModule {}
