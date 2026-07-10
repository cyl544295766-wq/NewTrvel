export type TripPlaceType =
  'attraction' | 'hotel' | 'restaurant' | 'transport' | 'shopping' | 'custom';

export type TripPlace = {
  id: string;
  tripId: string;
  tripDayId: string | null;
  name: string;
  type: TripPlaceType;
  address: string | null;
  latitude: string | null;
  longitude: string | null;
  startTime: string | null;
  endTime: string | null;
  notes: string | null;
  sortOrder: number;
  isCompleted: boolean;
};

export type TripDay = {
  id: string;
  tripId: string;
  date: string;
  dayIndex: number;
  title: string | null;
  summary: string | null;
  places: TripPlace[];
};

export type TripPlaceInput = {
  tripDayId?: string;
  name: string;
  type?: TripPlaceType;
  address?: string;
  latitude?: string;
  longitude?: string;
  startTime?: string;
  endTime?: string;
  notes?: string;
  isCompleted?: boolean;
};
