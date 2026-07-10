import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { PackingListsController } from './packing-lists.controller';
import { PackingListsRepository } from './packing-lists.repository';
import { PackingListsService } from './packing-lists.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [PackingListsController],
  providers: [PackingListsRepository, PackingListsService],
  exports: [PackingListsRepository],
})
export class PackingListsModule {}
