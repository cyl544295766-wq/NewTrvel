import { Response } from 'express';
import { TripPdfController } from './trip-pdf.controller';
import { TripPdfService } from './trip-pdf.service';

describe('TripPdfController', () => {
  it('writes PDF download headers and content', async () => {
    const service = {
      export: jest.fn().mockResolvedValue({
        buffer: Buffer.from('%PDF-test'),
        filename: '上海周末.pdf',
      }),
    };
    const response = {
      setHeader: jest.fn(),
      end: jest.fn(),
    };
    const controller = new TripPdfController(service as unknown as TripPdfService);

    await controller.exportPdf(
      'trip-1',
      { id: 'user-1', username: 'traveler' },
      response as unknown as Response,
    );

    expect(service.export).toHaveBeenCalledWith('trip-1', 'user-1');
    expect(response.setHeader).toHaveBeenCalledWith('Content-Type', 'application/pdf');
    expect(response.setHeader).toHaveBeenCalledWith(
      'Content-Disposition',
      expect.stringContaining(encodeURIComponent('上海周末.pdf')),
    );
    expect(response.end).toHaveBeenCalledWith(Buffer.from('%PDF-test'));
  });
});
