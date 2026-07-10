import { ArrayUnique, IsArray, IsString } from 'class-validator';

export class ReorderJournalPhotosDto {
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  photoIds!: string[];
}
