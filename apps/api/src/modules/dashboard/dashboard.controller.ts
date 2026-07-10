import { Controller, Get, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { NotificationsService } from '../notifications/notifications.service';
import { DashboardService } from './dashboard.service';

@UseGuards(JwtAuthGuard)
@Controller('dashboard')
export class DashboardController {
  constructor(
    private readonly dashboardService: DashboardService,
    private readonly notificationsService: NotificationsService,
  ) {}

  @Get()
  findOne(@CurrentUser() currentUser: AuthenticatedUser) {
    void this.notificationsService.generateForUser(currentUser.id).catch(() => undefined);
    return this.dashboardService.findOne(currentUser);
  }
}
