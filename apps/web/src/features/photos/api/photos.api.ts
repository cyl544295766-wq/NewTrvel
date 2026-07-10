import { request } from '../../../services/http';
import { Photo, PhotoInput, PhotoUpdateInput } from '../types/photo.types';

export function getPhotos(tripId: string) {
  return request<{ photos: Photo[] }>(`/trips/${tripId}/photos`);
}

export function getPhoto(tripId: string, photoId: string) {
  return request<{ photo: Photo }>(`/trips/${tripId}/photos/${photoId}`);
}

export function createPhoto(tripId: string, input: PhotoInput) {
  return request<{ photo: Photo }>(`/trips/${tripId}/photos`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updatePhoto(tripId: string, photoId: string, input: PhotoUpdateInput) {
  return request<{ photo: Photo }>(`/trips/${tripId}/photos/${photoId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function deletePhoto(tripId: string, photoId: string) {
  return request<{ success: true }>(`/trips/${tripId}/photos/${photoId}`, { method: 'DELETE' });
}
