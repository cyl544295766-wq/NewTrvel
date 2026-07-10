import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PhotosModule } from '../photos/photos.module';
import { TripExpensesModule } from '../trip-expenses/trip-expenses.module';
import { TripsModule } from '../trips/trips.module';
import { DashboardController } from './dashboard.controller';
import { DashboardService } from './dashboard.service';

@Module({
  imports: [JwtModule.register({}), TripsModule, TripExpensesModule, PhotosModule],
  controllers: [DashboardController],
  providers: [DashboardService],
})
export class DashboardModule {}
