import { request } from '../../../services/http';
import {
  TravelDocument,
  TravelDocumentInput,
  TravelDocumentUpdateInput,
} from '../types/travel-document.types';

export function getTravelDocuments(tripId: string) {
  return request<{ documents: TravelDocument[] }>(`/trips/${tripId}/documents`);
}

export function getTravelDocument(tripId: string, documentId: string) {
  return request<{ document: TravelDocument }>(`/trips/${tripId}/documents/${documentId}`);
}

export function createTravelDocument(tripId: string, input: TravelDocumentInput) {
  return request<{ document: TravelDocument }>(`/trips/${tripId}/documents`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateTravelDocument(
  tripId: string,
  documentId: string,
  input: TravelDocumentUpdateInput,
) {
  return request<{ document: TravelDocument }>(`/trips/${tripId}/documents/${documentId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function deleteTravelDocument(tripId: string, documentId: string) {
  return request<{ success: true }>(`/trips/${tripId}/documents/${documentId}`, {
    method: 'DELETE',
  });
}
