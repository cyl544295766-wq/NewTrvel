import { request } from '../../../services/http';
import { PlaceSuggestion } from '../types/place.types';

export function getPlaceSuggestions(keyword: string, city?: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ keyword, limit: '8' });
  if (city?.trim()) query.set('city', city.trim());
  return request<{ suggestions: PlaceSuggestion[] }>(`/places/suggestions?${query}`, { signal });
}
