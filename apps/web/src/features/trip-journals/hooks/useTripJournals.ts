import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dashboardQueryKey } from '../../dashboard/hooks/useDashboard';
import {
  createTripJournal,
  deleteTripJournal,
  getTripJournal,
  getTripJournals,
  reorderJournalPhotos,
  updateTripJournal,
} from '../api/trip-journals.api';
import { TripJournalInput, TripJournalUpdateInput } from '../types/trip-journal.types';

const journalsKey = (tripId: string) => ['trip-journals', tripId];
const journalKey = (tripId: string, journalId: string) => [...journalsKey(tripId), journalId];

function useJournalInvalidation(tripId: string) {
  const queryClient = useQueryClient();
  return (journalId?: string) => {
    queryClient.invalidateQueries({ queryKey: journalsKey(tripId) });
    if (journalId) queryClient.invalidateQueries({ queryKey: journalKey(tripId, journalId) });
    queryClient.invalidateQueries({ queryKey: dashboardQueryKey });
  };
}

export function useTripJournals(tripId: string) {
  return useQuery({
    queryKey: journalsKey(tripId),
    queryFn: () => getTripJournals(tripId),
    enabled: Boolean(tripId),
  });
}

export function useTripJournal(tripId: string, journalId: string) {
  return useQuery({
    queryKey: journalKey(tripId, journalId),
    queryFn: () => getTripJournal(tripId, journalId),
    enabled: Boolean(tripId && journalId),
  });
}

export function useCreateTripJournal(tripId: string) {
  const invalidate = useJournalInvalidation(tripId);
  return useMutation({
    mutationFn: (input: TripJournalInput) => createTripJournal(tripId, input),
    onSuccess: () => invalidate(),
  });
}

export function useUpdateTripJournal(tripId: string) {
  const invalidate = useJournalInvalidation(tripId);
  return useMutation({
    mutationFn: ({ journalId, input }: { journalId: string; input: TripJournalUpdateInput }) =>
      updateTripJournal(tripId, journalId, input),
    onSuccess: (_, variables) => invalidate(variables.journalId),
  });
}

export function useDeleteTripJournal(tripId: string) {
  const invalidate = useJournalInvalidation(tripId);
  return useMutation({
    mutationFn: (journalId: string) => deleteTripJournal(tripId, journalId),
    onSuccess: () => invalidate(),
  });
}

export function useReorderJournalPhotos(tripId: string) {
  const invalidate = useJournalInvalidation(tripId);
  return useMutation({
    mutationFn: ({ journalId, photoIds }: { journalId: string; photoIds: string[] }) =>
      reorderJournalPhotos(tripId, journalId, photoIds),
    onSuccess: (_, variables) => invalidate(variables.journalId),
  });
}
