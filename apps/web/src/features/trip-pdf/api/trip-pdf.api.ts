import { env } from '../../../config/env';
import { TripPdfDownload } from '../types/trip-pdf.types';

export async function downloadTripPdf(tripId: string): Promise<TripPdfDownload> {
  const response = await fetch(`${env.apiBaseUrl}/trips/${tripId}/export/pdf`, {
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error(`PDF export failed: ${response.status}`);
  }

  return {
    blob: await response.blob(),
    filename: getFilename(response.headers.get('Content-Disposition')),
  };
}

function getFilename(contentDisposition: string | null) {
  const encoded = contentDisposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return '旅行行程.pdf';
    }
  }

  return contentDisposition?.match(/filename="([^"]+)"/i)?.[1] ?? '旅行行程.pdf';
}
