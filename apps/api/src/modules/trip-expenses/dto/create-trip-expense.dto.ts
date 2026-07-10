import { TripExpenseCategory } from '@prisma/client';
import {
  ArrayNotEmpty,
  IsArray,
  IsDateString,
  IsEnum,
  IsNumberString,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';

export class CreateTripExpenseDto {
  @IsString()
  @MinLength(1)
  @MaxLength(120)
  title!: string;

  @IsNumberString()
  amount!: string;

  @IsOptional()
  @IsString()
  @MaxLength(8)
  currency?: string;

  @IsOptional()
  @IsEnum(TripExpenseCategory)
  category?: TripExpenseCategory;

  @IsString()
  payerUserId!: string;

  @IsArray()
  @ArrayNotEmpty()
  @IsString({ each: true })
  shareUserIds!: string[];

  @IsDateString()
  spentAt!: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  notes?: string;
}
