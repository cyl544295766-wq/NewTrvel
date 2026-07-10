import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTripPlace,
  deleteTripPlace,
  generateTripDays,
  getTripDays,
  updateTripDay,
} from '../api/itinerary.api';
import { TripPlaceInput } from '../types/itinerary.types';

const itineraryKey = (tripId: string) => ['itinerary', tripId];

export function useTripDays(tripId: string) {
  return useQuery({
    queryKey: itineraryKey(tripId),
    queryFn: () => getTripDays(tripId),
    enabled: Boolean(tripId),
  });
}

export function useGenerateTripDays(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => generateTripDays(tripId),
    onSuccess: (data) => queryClient.setQueryData(itineraryKey(tripId), data),
  });
}

export function useUpdateTripDay(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dayId, title, summary }: { dayId: string; title?: string; summary?: string }) =>
      updateTripDay(tripId, dayId, { title, summary }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: itineraryKey(tripId) }),
  });
}

export function useCreateTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TripPlaceInput) => createTripPlace(tripId, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: itineraryKey(tripId) }),
  });
}

export function useDeleteTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (placeId: string) => deleteTripPlace(tripId, placeId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: itineraryKey(tripId) }),
  });
}
