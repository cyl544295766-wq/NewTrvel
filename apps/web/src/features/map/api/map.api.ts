import { request } from '../../../services/http';
import { TripRouteDay } from '../types/map.types';

export function getTripRoute(tripId: string) {
  return request<{ route: TripRouteDay[] }>(`/trips/${tripId}/route`);
}
