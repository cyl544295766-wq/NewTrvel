import { request } from '../../../services/http';
import {
  PackingItem,
  PackingItemInput,
  PackingItemUpdateInput,
  PackingList,
  PackingListInput,
} from '../types/packing-list.types';

const listPath = (tripId: string) => `/trips/${tripId}/packing-lists`;

export function getPackingLists(tripId: string) {
  return request<{ packingLists: PackingList[] }>(listPath(tripId));
}

export function createPackingList(tripId: string, input: PackingListInput) {
  return request<{ packingList: PackingList }>(listPath(tripId), {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updatePackingList(tripId: string, listId: string, input: PackingListInput) {
  return request<{ packingList: PackingList }>(`${listPath(tripId)}/${listId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function deletePackingList(tripId: string, listId: string) {
  return request<{ success: true }>(`${listPath(tripId)}/${listId}`, { method: 'DELETE' });
}

export function duplicatePackingList(tripId: string, listId: string) {
  return request<{ packingList: PackingList }>(`${listPath(tripId)}/${listId}/duplicate`, {
    method: 'POST',
  });
}

export function createPackingItem(tripId: string, listId: string, input: PackingItemInput) {
  return request<{ item: PackingItem }>(`${listPath(tripId)}/${listId}/items`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updatePackingItem(
  tripId: string,
  listId: string,
  itemId: string,
  input: PackingItemUpdateInput,
) {
  return request<{ item: PackingItem }>(`${listPath(tripId)}/${listId}/items/${itemId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function deletePackingItem(tripId: string, listId: string, itemId: string) {
  return request<{ success: true }>(`${listPath(tripId)}/${listId}/items/${itemId}`, {
    method: 'DELETE',
  });
}
