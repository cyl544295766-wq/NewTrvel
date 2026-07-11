import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TripMembersModule } from '../trip-members/trip-members.module';
import { WeatherController } from './weather.controller';
import { WeatherRepository } from './weather.repository';
import { WeatherService } from './weather.service';

@Module({
  imports: [JwtModule.register({}), TripMembersModule],
  controllers: [WeatherController],
  providers: [WeatherRepository, WeatherService],
})
export class WeatherModule {}
