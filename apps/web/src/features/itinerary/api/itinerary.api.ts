import { request } from '../../../services/http';
import { TripDay, TripPlace, TripPlaceInput } from '../types/itinerary.types';

export function getTripDays(tripId: string) {
  return request<{ days: TripDay[] }>(`/trips/${tripId}/days`);
}

export function generateTripDays(tripId: string) {
  return request<{ days: TripDay[] }>(`/trips/${tripId}/days/generate`, { method: 'POST' });
}

export function updateTripDay(
  tripId: string,
  dayId: string,
  input: { title?: string; summary?: string },
) {
  return request<{ day: TripDay }>(`/trips/${tripId}/days/${dayId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function createTripPlace(tripId: string, input: TripPlaceInput) {
  return request<{ place: TripPlace }>(`/trips/${tripId}/places`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateTripPlace(tripId: string, placeId: string, input: Partial<TripPlaceInput>) {
  return request<{ place: TripPlace }>(`/trips/${tripId}/places/${placeId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function movePlace(tripId: string, placeId: string, tripDayId: string) {
  return request<{ place: TripPlace }>(`/trips/${tripId}/places/${placeId}/move`, {
    method: 'PATCH',
    body: JSON.stringify({ tripDayId }),
  });
}

export function reorderTripPlaces(tripId: string, dayId: string, placeIds: string[]) {
  return request<{ places: TripPlace[] }>(`/trips/${tripId}/days/${dayId}/places/reorder`, {
    method: 'PATCH',
    body: JSON.stringify({ placeIds }),
  });
}

export function deleteTripPlace(tripId: string, placeId: string) {
  return request<{ success: true }>(`/trips/${tripId}/places/${placeId}`, { method: 'DELETE' });
}
