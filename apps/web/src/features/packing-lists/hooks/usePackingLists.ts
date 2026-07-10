import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dashboardQueryKey } from '../../dashboard/hooks/useDashboard';
import {
  createPackingItem,
  createPackingList,
  deletePackingItem,
  deletePackingList,
  duplicatePackingList,
  getPackingLists,
  updatePackingItem,
  updatePackingList,
} from '../api/packing-lists.api';
import {
  PackingItemInput,
  PackingItemUpdateInput,
  PackingListInput,
} from '../types/packing-list.types';

const packingListsKey = (tripId: string) => ['packing-lists', tripId];

function usePackingMutationInvalidation(tripId: string) {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: packingListsKey(tripId) });
    queryClient.invalidateQueries({ queryKey: dashboardQueryKey });
  };
}

export function usePackingLists(tripId: string) {
  return useQuery({
    queryKey: packingListsKey(tripId),
    queryFn: () => getPackingLists(tripId),
    enabled: Boolean(tripId),
  });
}

export function useCreatePackingList(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: (input: PackingListInput) => createPackingList(tripId, input),
    onSuccess: invalidate,
  });
}

export function useUpdatePackingList(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: ({ listId, input }: { listId: string; input: PackingListInput }) =>
      updatePackingList(tripId, listId, input),
    onSuccess: invalidate,
  });
}

export function useDeletePackingList(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: (listId: string) => deletePackingList(tripId, listId),
    onSuccess: invalidate,
  });
}

export function useDuplicatePackingList(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: (listId: string) => duplicatePackingList(tripId, listId),
    onSuccess: invalidate,
  });
}

export function useCreatePackingItem(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: ({ listId, input }: { listId: string; input: PackingItemInput }) =>
      createPackingItem(tripId, listId, input),
    onSuccess: invalidate,
  });
}

export function useUpdatePackingItem(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: ({
      listId,
      itemId,
      input,
    }: {
      listId: string;
      itemId: string;
      input: PackingItemUpdateInput;
    }) => updatePackingItem(tripId, listId, itemId, input),
    onSuccess: invalidate,
  });
}

export function useDeletePackingItem(tripId: string) {
  const invalidate = usePackingMutationInvalidation(tripId);
  return useMutation({
    mutationFn: ({ listId, itemId }: { listId: string; itemId: string }) =>
      deletePackingItem(tripId, listId, itemId),
    onSuccess: invalidate,
  });
}
