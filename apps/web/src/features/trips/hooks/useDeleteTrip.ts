import { useMutation, useQueryClient } from '@tanstack/react-query';
import { deleteTrip } from '../api/trips.api';
import { tripsQueryKey } from './queryKeys';

export function useDeleteTrip() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (tripId: string) => deleteTrip(tripId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripsQueryKey }),
  });
}
