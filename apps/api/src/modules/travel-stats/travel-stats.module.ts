import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TravelStatsController } from './travel-stats.controller';
import { TravelStatsRepository } from './travel-stats.repository';
import { TravelStatsService } from './travel-stats.service';

@Module({
  imports: [JwtModule.register({})],
  controllers: [TravelStatsController],
  providers: [TravelStatsRepository, TravelStatsService],
})
export class TravelStatsModule {}
