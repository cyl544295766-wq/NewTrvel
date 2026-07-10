import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dashboardQueryKey } from '../../dashboard/hooks/useDashboard';
import {
  createTravelDocument,
  deleteTravelDocument,
  getTravelDocuments,
  updateTravelDocument,
} from '../api/travel-documents.api';
import { TravelDocumentInput, TravelDocumentUpdateInput } from '../types/travel-document.types';

const travelDocumentsKey = (tripId: string) => ['travel-documents', tripId];

function invalidateDocumentDependencies(
  queryClient: ReturnType<typeof useQueryClient>,
  tripId: string,
) {
  queryClient.invalidateQueries({ queryKey: travelDocumentsKey(tripId) });
  queryClient.invalidateQueries({ queryKey: dashboardQueryKey });
}

export function useTravelDocuments(tripId: string) {
  return useQuery({
    queryKey: travelDocumentsKey(tripId),
    queryFn: () => getTravelDocuments(tripId),
    enabled: Boolean(tripId),
  });
}

export function useCreateTravelDocument(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TravelDocumentInput) => createTravelDocument(tripId, input),
    onSuccess: () => invalidateDocumentDependencies(queryClient, tripId),
  });
}

export function useUpdateTravelDocument(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, input }: { documentId: string; input: TravelDocumentUpdateInput }) =>
      updateTravelDocument(tripId, documentId, input),
    onSuccess: () => invalidateDocumentDependencies(queryClient, tripId),
  });
}

export function useDeleteTravelDocument(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (documentId: string) => deleteTravelDocument(tripId, documentId),
    onSuccess: () => invalidateDocumentDependencies(queryClient, tripId),
  });
}
