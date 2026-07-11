import { Controller, Get, Query, UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { PlaceSuggestionsQueryDto } from './dto/place-suggestions-query.dto';
import { PlacesService } from './places.service';

@UseGuards(JwtAuthGuard)
@Controller('places')
export class PlacesController {
  constructor(private readonly placesService: PlacesService) {}

  @Get('suggestions')
  getSuggestions(@Query() query: PlaceSuggestionsQueryDto) {
    return this.placesService.getSuggestions(query.keyword, query.city, query.limit);
  }
}
