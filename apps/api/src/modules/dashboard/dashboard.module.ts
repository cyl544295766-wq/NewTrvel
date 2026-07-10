import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PackingListsModule } from '../packing-lists/packing-lists.module';
import { PhotosModule } from '../photos/photos.module';
import { TripExpensesModule } from '../trip-expenses/trip-expenses.module';
import { TripJournalsModule } from '../trip-journals/trip-journals.module';
import { TravelDocumentsModule } from '../travel-documents/travel-documents.module';
import { TripsModule } from '../trips/trips.module';
import { DashboardController } from './dashboard.controller';
import { DashboardService } from './dashboard.service';

@Module({
  imports: [
    JwtModule.register({}),
    TripsModule,
    TripExpensesModule,
    TripJournalsModule,
    PackingListsModule,
    PhotosModule,
    TravelDocumentsModule,
  ],
  controllers: [DashboardController],
  providers: [DashboardService],
})
export class DashboardModule {}
