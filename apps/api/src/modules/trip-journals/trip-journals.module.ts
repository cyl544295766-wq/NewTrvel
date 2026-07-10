import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripJournalsController } from './trip-journals.controller';
import { TripJournalsRepository } from './trip-journals.repository';
import { TripJournalsService } from './trip-journals.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripJournalsController],
  providers: [TripJournalsRepository, TripJournalsService],
  exports: [TripJournalsRepository],
})
export class TripJournalsModule {}
