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

export class UpdateTripJournalDto {
  @IsOptional()
  @IsString()
  @MinLength(1)
  @MaxLength(200)
  title?: string;

  @IsOptional()
  @IsString()
  @MaxLength(20000)
  content?: string;

  @IsOptional()
  @IsString()
  tripDayId?: string | null;

  @IsOptional()
  @IsString()
  tripPlaceId?: string | null;

  @IsOptional()
  @IsIn(journalMoods)
  mood?: string | null;

  @IsOptional()
  @IsBoolean()
  isDraft?: boolean;

  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  photoIds?: string[];
}
