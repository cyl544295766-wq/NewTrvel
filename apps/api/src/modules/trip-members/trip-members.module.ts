import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { NotificationsModule } from '../notifications/notifications.module';
import { UsersModule } from '../users/users.module';
import { TripMembersController } from './trip-members.controller';
import { TripMembersRepository } from './trip-members.repository';
import { TripMembersService } from './trip-members.service';

@Module({
  imports: [JwtModule.register({}), UsersModule, NotificationsModule],
  controllers: [TripMembersController],
  providers: [TripMembersRepository, TripMembersService],
  exports: [TripMembersService],
})
export class TripMembersModule {}
