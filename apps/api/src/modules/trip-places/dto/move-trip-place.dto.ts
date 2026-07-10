import { IsString } from 'class-validator';

export class MoveTripPlaceDto {
  @IsString()
  tripDayId!: string;
}
