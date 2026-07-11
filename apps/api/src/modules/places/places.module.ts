import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PlacesController } from './places.controller';
import { PlacesService } from './places.service';

@Module({
  imports: [JwtModule.register({})],
  controllers: [PlacesController],
  providers: [PlacesService],
})
export class PlacesModule {}
