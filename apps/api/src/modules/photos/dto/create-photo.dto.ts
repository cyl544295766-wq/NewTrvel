import { IsBoolean, IsDateString, IsOptional, IsString, MaxLength } from 'class-validator';

export class CreatePhotoDto {
  @IsString()
  url!: string;

  @IsOptional()
  @IsString()
  @MaxLength(300)
  caption?: string;

  @IsOptional()
  @IsDateString()
  shotAt?: string;

  @IsOptional()
  @IsString()
  tripDayId?: string;

  @IsOptional()
  @IsString()
  tripPlaceId?: string;

  @IsOptional()
  @IsBoolean()
  isCover?: boolean;
}
