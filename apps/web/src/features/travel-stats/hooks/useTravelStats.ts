import { useQuery } from '@tanstack/react-query';
import {
  getExpenseCategoryStats,
  getMonthlyStats,
  getStatsOverview,
  getYearlyStats,
} from '../api/travel-stats.api';

export const travelStatsQueryKey = ['travel-stats'];

export function useStatsOverview() {
  return useQuery({
    queryKey: [...travelStatsQueryKey, 'overview'],
    queryFn: getStatsOverview,
  });
}

export function useYearlyStats(year: number) {
  return useQuery({
    queryKey: [...travelStatsQueryKey, 'yearly', year],
    queryFn: () => getYearlyStats(year),
  });
}

export function useMonthlyStats(year: number) {
  return useQuery({
    queryKey: [...travelStatsQueryKey, 'monthly', year],
    queryFn: () => getMonthlyStats(year),
  });
}

export function useExpenseCategoryStats() {
  return useQuery({
    queryKey: [...travelStatsQueryKey, 'expense-categories'],
    queryFn: getExpenseCategoryStats,
  });
}
