import { ArrayNotEmpty, IsArray, IsString } from 'class-validator';

export class ReorderTripPlacesDto {
  @IsArray()
  @ArrayNotEmpty()
  @IsString({ each: true })
  placeIds!: string[];
}
