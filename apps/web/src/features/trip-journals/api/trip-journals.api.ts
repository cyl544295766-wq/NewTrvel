import { request } from '../../../services/http';
import { TripJournal, TripJournalInput, TripJournalUpdateInput } from '../types/trip-journal.types';

const journalPath = (tripId: string) => `/trips/${tripId}/journals`;

export function getTripJournals(tripId: string) {
  return request<{ journals: TripJournal[] }>(journalPath(tripId));
}

export function getTripJournal(tripId: string, journalId: string) {
  return request<{ journal: TripJournal }>(`${journalPath(tripId)}/${journalId}`);
}

export function createTripJournal(tripId: string, input: TripJournalInput) {
  return request<{ journal: TripJournal }>(journalPath(tripId), {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateTripJournal(
  tripId: string,
  journalId: string,
  input: TripJournalUpdateInput,
) {
  return request<{ journal: TripJournal }>(`${journalPath(tripId)}/${journalId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function deleteTripJournal(tripId: string, journalId: string) {
  return request<{ success: true }>(`${journalPath(tripId)}/${journalId}`, {
    method: 'DELETE',
  });
}

export function reorderJournalPhotos(tripId: string, journalId: string, photoIds: string[]) {
  return request<{ journal: TripJournal }>(`${journalPath(tripId)}/${journalId}/photos/reorder`, {
    method: 'PATCH',
    body: JSON.stringify({ photoIds }),
  });
}
