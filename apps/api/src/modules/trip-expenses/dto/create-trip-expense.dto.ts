import { TripExpenseCategory } from '@prisma/client';
import { Type } from 'class-transformer';
import {
  IsArray,
  IsDateString,
  IsEnum,
  IsNumberString,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
  ValidateNested,
} from 'class-validator';

export class TripExpenseShareInputDto {
  @IsString()
  userId!: string;

  @IsNumberString()
  shareAmount!: string;
}

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

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  shareUserIds?: string[];

  @IsOptional()
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => TripExpenseShareInputDto)
  shares?: TripExpenseShareInputDto[];

  @IsDateString()
  spentAt!: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  notes?: string;
}
