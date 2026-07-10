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

export class CreateTravelDocumentDto {
  @IsEnum(TravelDocumentType)
  type!: TravelDocumentType;

  @IsString()
  @MinLength(1)
  @MaxLength(100)
  title!: string;

  @IsString()
  url!: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  notes?: string;

  @IsOptional()
  @IsString()
  tripDayId?: string;

  @IsOptional()
  @IsString()
  tripPlaceId?: string;

  @IsOptional()
  @IsDateString()
  expiredAt?: string;

  @IsOptional()
  @IsBoolean()
  isReminder?: boolean;
}
