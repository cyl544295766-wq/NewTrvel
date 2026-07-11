import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTripPlace,
  deleteTripPlace,
  generateTripDays,
  getTripDays,
  movePlace,
  reorderTripPlaces,
  updateTripDay,
  updateTripPlace,
} from '../api/itinerary.api';
import { TripPlaceInput } from '../types/itinerary.types';

const itineraryKey = (tripId: string) => ['itinerary', tripId];
const mapKey = (tripId: string) => ['trip-route', tripId];

function invalidatePlaceQueries(queryClient: ReturnType<typeof useQueryClient>, tripId: string) {
  queryClient.invalidateQueries({ queryKey: itineraryKey(tripId) });
  queryClient.invalidateQueries({ queryKey: mapKey(tripId) });
}

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
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}

export function useCreateTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TripPlaceInput) => createTripPlace(tripId, input),
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}

export function useUpdateTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ placeId, input }: { placeId: string; input: Partial<TripPlaceInput> }) =>
      updateTripPlace(tripId, placeId, input),
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}

export function useMoveTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ placeId, tripDayId }: { placeId: string; tripDayId: string }) =>
      movePlace(tripId, placeId, tripDayId),
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}

export function useReorderTripPlaces(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dayId, placeIds }: { dayId: string; placeIds: string[] }) =>
      reorderTripPlaces(tripId, dayId, placeIds),
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}

export function useDeleteTripPlace(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (placeId: string) => deleteTripPlace(tripId, placeId),
    onSuccess: () => invalidatePlaceQueries(queryClient, tripId),
  });
}
