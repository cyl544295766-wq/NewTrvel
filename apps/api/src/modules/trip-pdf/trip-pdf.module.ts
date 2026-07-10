import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripPdfController } from './trip-pdf.controller';
import { TripPdfRepository } from './trip-pdf.repository';
import { TripPdfService } from './trip-pdf.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripPdfController],
  providers: [TripPdfRepository, TripPdfService],
})
export class TripPdfModule {}
