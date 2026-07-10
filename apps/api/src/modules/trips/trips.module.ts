import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripsController } from './trips.controller';
import { TripsRepository } from './trips.repository';
import { TripsService } from './trips.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripsController],
  providers: [TripsRepository, TripsService],
  exports: [TripsRepository],
})
export class TripsModule {}
