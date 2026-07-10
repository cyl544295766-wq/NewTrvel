import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { duplicateTrip } from '../api/trips.api';
import { tripsQueryKey } from './queryKeys';

export function useDuplicateTrip() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (tripId: string) => duplicateTrip(tripId),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: tripsQueryKey });
      navigate(`/trips/${data.trip.id}`);
    },
  });
}
