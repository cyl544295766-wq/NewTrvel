export type TripRoutePlaceType =
  'attraction' | 'hotel' | 'restaurant' | 'transport' | 'shopping' | 'custom';

export type TripRoutePlace = {
  id: string;
  name: string;
  type: TripRoutePlaceType;
  latitude: string;
  longitude: string;
  sortOrder: number;
};

export type TripRouteDay = {
  dayId: string;
  dayIndex: number;
  date: string;
  places: TripRoutePlace[];
};
