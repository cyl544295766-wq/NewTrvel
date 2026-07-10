import { useMutation, useQueryClient } from '@tanstack/react-query';
import { favoriteTrip } from '../api/trips.api';
import { tripQueryKey, tripsQueryKey } from './queryKeys';

export function useFavoriteTrip() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (tripId: string) => favoriteTrip(tripId),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: tripsQueryKey });
      queryClient.invalidateQueries({ queryKey: tripQueryKey(data.trip.id) });
    },
  });
}
