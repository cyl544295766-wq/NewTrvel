import { Controller, Get, Query, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { TravelStatsService } from './travel-stats.service';

@UseGuards(JwtAuthGuard)
@Controller('stats')
export class TravelStatsController {
  constructor(private readonly travelStatsService: TravelStatsService) {}

  @Get('overview')
  overview(@CurrentUser() user: AuthenticatedUser) {
    return this.travelStatsService.getOverview(user.id);
  }

  @Get('yearly')
  yearly(@CurrentUser() user: AuthenticatedUser, @Query('year') year?: string) {
    return this.travelStatsService.getYearly(user.id, year);
  }

  @Get('monthly')
  monthly(@CurrentUser() user: AuthenticatedUser, @Query('year') year?: string) {
    return this.travelStatsService.getMonthly(user.id, year);
  }

  @Get('expense-categories')
  expenseCategories(@CurrentUser() user: AuthenticatedUser) {
    return this.travelStatsService.getExpenseCategories(user.id);
  }
}
