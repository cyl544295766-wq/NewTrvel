import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { TripExpensesController } from './trip-expenses.controller';
import { TripExpensesRepository } from './trip-expenses.repository';
import { TripExpensesService } from './trip-expenses.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [TripExpensesController],
  providers: [TripExpensesRepository, TripExpensesService],
  exports: [TripExpensesRepository],
})
export class TripExpensesModule {}
