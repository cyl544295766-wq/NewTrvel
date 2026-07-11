import { useQuery } from '@tanstack/react-query';
import { getTripDayWeather, getTripWeather } from '../api/weather.api';

export const weatherQueryKey = ['weather'];

export function useTripWeather(tripId: string, enabled = true) {
  return useQuery({
    queryKey: [...weatherQueryKey, tripId],
    queryFn: () => getTripWeather(tripId),
    enabled: enabled && Boolean(tripId),
    staleTime: 30 * 60 * 1000,
  });
}

export function useTripDayWeather(tripId: string, tripDayId: string, enabled = true) {
  return useQuery({
    queryKey: [...weatherQueryKey, tripId, tripDayId],
    queryFn: () => getTripDayWeather(tripId, tripDayId),
    enabled: enabled && Boolean(tripId) && Boolean(tripDayId),
    staleTime: 30 * 60 * 1000,
  });
}
