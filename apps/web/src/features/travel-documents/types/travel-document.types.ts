export type TravelDocumentType =
  'passport' | 'visa' | 'flight' | 'hotel' | 'ticket' | 'insurance' | 'other';

export const travelDocumentTypeLabels: Record<TravelDocumentType, string> = {
  passport: '护照',
  visa: '签证',
  flight: '机票',
  hotel: '酒店',
  ticket: '门票',
  insurance: '保险',
  other: '其他',
};

export const travelDocumentTypes = Object.keys(travelDocumentTypeLabels) as TravelDocumentType[];

export type TravelDocument = {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  type: TravelDocumentType;
  title: string;
  url: string;
  notes: string | null;
  expiredAt: string | null;
  isReminder: boolean;
  createdAt: string;
  updatedAt: string;
  tripDay: { id: string; dayIndex: number; date: string } | null;
  tripPlace: { id: string; name: string } | null;
};

export type TravelDocumentInput = {
  type: TravelDocumentType;
  title: string;
  url: string;
  notes?: string;
  tripDayId?: string;
  tripPlaceId?: string;
  expiredAt?: string;
  isReminder?: boolean;
};

export type TravelDocumentUpdateInput = {
  type?: TravelDocumentType;
  title?: string;
  url?: string;
  notes?: string | null;
  tripDayId?: string | null;
  tripPlaceId?: string | null;
  expiredAt?: string | null;
  isReminder?: boolean;
};
