import { useQuery } from '@tanstack/react-query';
import { getDashboard } from '../api/dashboard.api';

export const dashboardQueryKey = ['dashboard'];

export function useDashboard() {
  return useQuery({
    queryKey: dashboardQueryKey,
    queryFn: getDashboard,
  });
}
