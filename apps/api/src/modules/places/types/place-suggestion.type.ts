import { TripPlaceType } from '@prisma/client';

export type PlaceSuggestion = {
  id: string;
  name: string;
  address: string | null;
  district: string | null;
  city: string | null;
  province: string | null;
  type: TripPlaceType;
  sourceType: string | null;
  latitude: string | null;
  longitude: string | null;
};
