import { TravelDocumentType } from '@prisma/client';
import {
  IsBoolean,
  IsDateString,
  IsEnum,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';

export class UpdateTravelDocumentDto {
  @IsOptional()
  @IsEnum(TravelDocumentType)
  type?: TravelDocumentType;

  @IsOptional()
  @IsString()
  @MinLength(1)
  @MaxLength(100)
  title?: string;

  @IsOptional()
  @IsString()
  url?: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  notes?: string | null;

  @IsOptional()
  @IsString()
  tripDayId?: string | null;

  @IsOptional()
  @IsString()
  tripPlaceId?: string | null;

  @IsOptional()
  @IsDateString()
  expiredAt?: string | null;

  @IsOptional()
  @IsBoolean()
  isReminder?: boolean;
}
