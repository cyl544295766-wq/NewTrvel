import { request } from '../../../services/http';
import { Trip, TripInput, TripUpdateInput } from '../types/trip.types';

export function getTrips(): Promise<{ trips: Trip[] }> {
  return request<{ trips: Trip[] }>('/trips');
}

export function getTrip(tripId: string): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}`);
}

export function createTrip(input: TripInput): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>('/trips', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateTrip(tripId: string, input: TripUpdateInput): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function archiveTrip(tripId: string): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}/archive`, {
    method: 'PATCH',
  });
}

export function duplicateTrip(tripId: string): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}/duplicate`, {
    method: 'POST',
  });
}

export function favoriteTrip(tripId: string): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}/favorite`, {
    method: 'PATCH',
  });
}

export function deleteTrip(tripId: string): Promise<{ trip: Trip }> {
  return request<{ trip: Trip }>(`/trips/${tripId}`, {
    method: 'DELETE',
  });
}
