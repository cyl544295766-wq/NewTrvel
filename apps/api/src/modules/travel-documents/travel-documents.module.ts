import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TravelDocumentsController } from './travel-documents.controller';
import { TravelDocumentsRepository } from './travel-documents.repository';
import { TravelDocumentsService } from './travel-documents.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TravelDocumentsController],
  providers: [TravelDocumentsRepository, TravelDocumentsService],
  exports: [TravelDocumentsRepository],
})
export class TravelDocumentsModule {}
