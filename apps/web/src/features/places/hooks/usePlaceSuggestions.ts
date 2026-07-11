import { useQuery } from '@tanstack/react-query';
import { getPlaceSuggestions } from '../api/places.api';

export function usePlaceSuggestions(keyword: string, city?: string) {
  const normalizedKeyword = keyword.trim();
  return useQuery({
    queryKey: ['place-suggestions', normalizedKeyword, city?.trim() ?? ''],
    queryFn: ({ signal }) => getPlaceSuggestions(normalizedKeyword, city, signal),
    enabled: normalizedKeyword.length >= 2,
    staleTime: 10 * 60 * 1000,
    gcTime: 20 * 60 * 1000,
    retry: 1,
  });
}
