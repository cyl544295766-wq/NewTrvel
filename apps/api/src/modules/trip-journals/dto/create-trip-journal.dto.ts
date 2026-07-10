import {
  ArrayUnique,
  IsArray,
  IsBoolean,
  IsIn,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';

const journalMoods = ['happy', 'excited', 'tired', 'sad', 'relaxed'];

export class CreateTripJournalDto {
  @IsString()
  @MinLength(1)
  @MaxLength(200)
  title!: string;

  @IsString()
  @MaxLength(20000)
  content!: string;

  @IsOptional()
  @IsString()
  tripDayId?: string;

  @IsOptional()
  @IsString()
  tripPlaceId?: string;

  @IsOptional()
  @IsIn(journalMoods)
  mood?: string;

  @IsOptional()
  @IsBoolean()
  isDraft?: boolean;

  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  photoIds?: string[];
}
