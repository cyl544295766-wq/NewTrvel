import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreateTripExpenseDto } from './dto/create-trip-expense.dto';
import { UpdateTripExpenseDto } from './dto/update-trip-expense.dto';
import { TripExpensesService } from './trip-expenses.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/expenses')
export class TripExpensesController {
  constructor(private readonly tripExpensesService: TripExpensesService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.tripExpensesService.findAll(tripId, user.id);
  }

  @Post()
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateTripExpenseDto,
  ) {
    return this.tripExpensesService.create(tripId, user.id, dto);
  }

  @Patch(':expenseId')
  update(
    @Param('tripId') tripId: string,
    @Param('expenseId') expenseId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateTripExpenseDto,
  ) {
    return this.tripExpensesService.update(tripId, expenseId, user.id, dto);
  }

  @Delete(':expenseId')
  delete(
    @Param('tripId') tripId: string,
    @Param('expenseId') expenseId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.tripExpensesService.delete(tripId, expenseId, user.id);
  }
}
