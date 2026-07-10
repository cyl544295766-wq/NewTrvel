import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { DatabaseModule } from './database/database.module';
import { DashboardModule } from './modules/dashboard/dashboard.module';
import { AuthModule } from './modules/auth/auth.module';
import { HealthModule } from './modules/health/health.module';
import { PackingListsModule } from './modules/packing-lists/packing-lists.module';
import { PhotosModule } from './modules/photos/photos.module';
import { TripDaysModule } from './modules/trip-days/trip-days.module';
import { TripExpensesModule } from './modules/trip-expenses/trip-expenses.module';
import { TripPlacesModule } from './modules/trip-places/trip-places.module';
import { TripJournalsModule } from './modules/trip-journals/trip-journals.module';
import { TravelDocumentsModule } from './modules/travel-documents/travel-documents.module';
import { TripsModule } from './modules/trips/trips.module';
import { UsersModule } from './modules/users/users.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: ['.env', '../../.env'],
    }),
    DatabaseModule,
    HealthModule,
    UsersModule,
    AuthModule,
    DashboardModule,
    TripsModule,
    TripDaysModule,
    TripPlacesModule,
    TripExpensesModule,
    TripJournalsModule,
    PackingListsModule,
    PhotosModule,
    TravelDocumentsModule,
  ],
})
export class AppModule {}
