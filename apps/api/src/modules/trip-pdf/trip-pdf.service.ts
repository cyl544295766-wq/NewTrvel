import { ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import PdfPrinter from 'pdfmake';
import { TFontDictionary } from 'pdfmake/interfaces';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { TripMembersService } from '../trip-members/trip-members.service';
import { buildTripPdfDocument, PreparedPdfPhoto } from './trip-pdf.document';
import { preparePdfImage } from './trip-pdf-image';
import { TripPdfData, TripPdfRepository } from './trip-pdf.repository';

const maxSelectedPhotos = 12;

@Injectable()
export class TripPdfService {
  constructor(
    private readonly tripPdfRepository: TripPdfRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async export(tripId: string, userId: string) {
    const trip = await this.tripPdfRepository.findTripForExport(tripId);
    if (!trip) throw new NotFoundException('旅行不存在');

    try {
      await this.tripMembersService.requireTripMember(tripId, userId);
    } catch (error) {
      if (error instanceof NotFoundException) {
        throw new ForbiddenException('没有权限导出该旅行');
      }
      throw error;
    }

    const coverUrl = trip.photos.find((photo) => photo.isCover)?.url ?? trip.coverImageUrl;
    const coverImage = coverUrl ? await preparePdfImage(coverUrl, 1200) : null;
    const photos = await this.preparePhotos(trip);
    const definition = buildTripPdfDocument(trip, coverImage, photos);
    const buffer = await this.renderPdf(definition);

    return { buffer, filename: `${sanitizeFilename(trip.title)}.pdf` };
  }

  private async preparePhotos(trip: TripPdfData) {
    const prepared: PreparedPdfPhoto[] = [];
    for (const photo of trip.photos.slice(0, maxSelectedPhotos)) {
      const image = await preparePdfImage(photo.url, 720);
      if (!image) continue;
      prepared.push({
        id: photo.id,
        image,
        caption: photo.caption,
        date: photo.shotAt ?? photo.tripDay?.date ?? photo.createdAt,
        placeName: photo.tripPlace?.name ?? null,
      });
    }
    return prepared;
  }

  private renderPdf(definition: Parameters<PdfPrinter['createPdfKitDocument']>[0]) {
    const fontPath = resolveFontPath();
    const fonts: TFontDictionary = {
      NotoSansSC: {
        normal: fontPath,
        bold: fontPath,
        italics: fontPath,
        bolditalics: fontPath,
      },
    };
    const printer = new PdfPrinter(fonts);
    const document = printer.createPdfKitDocument(definition);

    return new Promise<Buffer>((resolve, reject) => {
      const chunks: Buffer[] = [];
      document.on('data', (chunk: Buffer) => chunks.push(chunk));
      document.on('end', () => resolve(Buffer.concat(chunks)));
      document.on('error', reject);
      document.end();
    });
  }
}

function resolveFontPath() {
  const candidates = [
    join(__dirname, '..', '..', 'assets', 'fonts', 'NotoSansSC-VF.ttf'),
    join(process.cwd(), 'assets', 'fonts', 'NotoSansSC-VF.ttf'),
  ];
  const fontPath = candidates.find((candidate) => existsSync(candidate));
  if (!fontPath) throw new Error('PDF Chinese font asset not found');
  return fontPath;
}

function sanitizeFilename(value: string) {
  const sanitized = value.replace(/[<>:"/\\|?*\u0000-\u001F]/g, '').trim().slice(0, 80);
  return sanitized || '旅行行程';
}
