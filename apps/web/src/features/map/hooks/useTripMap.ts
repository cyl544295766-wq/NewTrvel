import { useQuery } from '@tanstack/react-query';
import { getTripRoute } from '../api/map.api';

export function useTripMap(tripId: string) {
  return useQuery({
    queryKey: ['trip-route', tripId],
    queryFn: () => getTripRoute(tripId),
    enabled: Boolean(tripId),
  });
}
