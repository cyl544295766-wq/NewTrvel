import { TripPlaceType } from '@prisma/client';

export function mapAmapPlaceType(typecode?: string | null): TripPlaceType {
  const group = typecode?.slice(0, 2);
  if (group === '05') return TripPlaceType.restaurant;
  if (group === '06') return TripPlaceType.shopping;
  if (group === '10') return TripPlaceType.hotel;
  if (group === '11' || group === '14') return TripPlaceType.attraction;
  if (group === '15') return TripPlaceType.transport;
  return TripPlaceType.custom;
}
