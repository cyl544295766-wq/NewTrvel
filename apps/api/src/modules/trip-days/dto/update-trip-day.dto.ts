import { IsOptional, IsString, MaxLength } from 'class-validator';

export class UpdateTripDayDto {
  @IsOptional()
  @IsString()
  @MaxLength(80)
  title?: string;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  summary?: string;
}
