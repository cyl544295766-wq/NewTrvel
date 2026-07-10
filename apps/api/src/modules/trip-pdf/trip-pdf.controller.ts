import { Controller, Get, Param, Res, UseGuards } from '@nestjs/common';
import { Response } from 'express';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { TripPdfService } from './trip-pdf.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/export')
export class TripPdfController {
  constructor(private readonly tripPdfService: TripPdfService) {}

  @Get('pdf')
  async exportPdf(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Res() response: Response,
  ) {
    const result = await this.tripPdfService.export(tripId, user.id);
    const encodedFilename = encodeURIComponent(result.filename);
    response.setHeader('Content-Type', 'application/pdf');
    response.setHeader(
      'Content-Disposition',
      `attachment; filename="trip.pdf"; filename*=UTF-8''${encodedFilename}`,
    );
    response.setHeader('Content-Length', result.buffer.length.toString());
    response.end(result.buffer);
  }
}
