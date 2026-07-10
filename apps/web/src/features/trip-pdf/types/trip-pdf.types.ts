export type TripPdfButtonStatus = 'idle' | 'loading' | 'success' | 'error';

export type TripPdfDownload = {
  blob: Blob;
  filename: string;
};
