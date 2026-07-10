import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  archiveTrip,
  createTrip,
  getTrip,
  getTrips,
  updateTrip,
} from '../api/trips.api';
import { TripInput, TripUpdateInput } from '../types/trip.types';
import { tripQueryKey, tripsQueryKey } from './queryKeys';

export function useTrips() {
  return useQuery({ queryKey: tripsQueryKey, queryFn: getTrips });
}

export function useTrip(tripId: string, enabled = true) {
  return useQuery({ queryKey: tripQueryKey(tripId), queryFn: () => getTrip(tripId), enabled });
}

export function useCreateTrip() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: TripInput) => createTrip(input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripsQueryKey }),
  });
}

export function useUpdateTrip(tripId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: TripUpdateInput) => updateTrip(tripId, input),
    onSuccess: (data) => {
      queryClient.setQueryData(tripQueryKey(tripId), data);
      queryClient.invalidateQueries({ queryKey: tripsQueryKey });
    },
  });
}

export function useArchiveTrip() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (tripId: string) => archiveTrip(tripId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripsQueryKey }),
  });
}

export { useDeleteTrip } from './useDeleteTrip';
export { useDuplicateTrip } from './useDuplicateTrip';
export { useFavoriteTrip } from './useFavoriteTrip';
export { tripQueryKey, tripsQueryKey } from './queryKeys';
