import { IsBoolean, IsDateString, IsOptional, IsString, MaxLength } from 'class-validator';

export class UpdatePhotoDto {
  @IsOptional()
  @IsString()
  @MaxLength(300)
  caption?: string;

  @IsOptional()
  @IsDateString()
  shotAt?: string | null;

  @IsOptional()
  @IsString()
  tripDayId?: string | null;

  @IsOptional()
  @IsString()
  tripPlaceId?: string | null;

  @IsOptional()
  @IsBoolean()
  isCover?: boolean;
}
