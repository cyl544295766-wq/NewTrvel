import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripDaysController } from './trip-days.controller';
import { TripDaysRepository } from './trip-days.repository';
import { TripDaysService } from './trip-days.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripDaysController],
  providers: [TripDaysRepository, TripDaysService],
})
export class TripDaysModule {}
