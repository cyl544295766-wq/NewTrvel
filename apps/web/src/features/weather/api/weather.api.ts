import { request } from '../../../services/http';
import { TripDayWeatherResponse, TripWeatherResponse } from '../types/weather.types';

export function getTripWeather(tripId: string): Promise<TripWeatherResponse> {
  return request<TripWeatherResponse>(`/trips/${tripId}/weather`);
}

export function getTripDayWeather(
  tripId: string,
  tripDayId: string,
): Promise<TripDayWeatherResponse> {
  return request<TripDayWeatherResponse>(`/trips/${tripId}/weather/${tripDayId}`);
}
