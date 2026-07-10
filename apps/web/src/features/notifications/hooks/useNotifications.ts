import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dashboardQueryKey } from '../../dashboard/hooks/useDashboard';
import {
  clearReadNotifications,
  getNotifications,
  getUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
} from '../api/notifications.api';

export const notificationQueryKey = ['notifications'];
export const unreadNotificationQueryKey = ['notifications', 'unread-count'];

export function useNotifications(page = 1, pageSize = 20, enabled = true) {
  return useQuery({
    queryKey: [...notificationQueryKey, page, pageSize],
    queryFn: () => getNotifications(page, pageSize),
    enabled,
  });
}

export function useUnreadNotificationCount() {
  return useQuery({
    queryKey: unreadNotificationQueryKey,
    queryFn: getUnreadNotificationCount,
    refetchInterval: 60_000,
  });
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => invalidateNotifications(queryClient),
  });
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => invalidateNotifications(queryClient),
  });
}

export function useClearReadNotifications() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: clearReadNotifications,
    onSuccess: () => invalidateNotifications(queryClient),
  });
}

function invalidateNotifications(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: notificationQueryKey });
  void queryClient.invalidateQueries({ queryKey: unreadNotificationQueryKey });
  void queryClient.invalidateQueries({ queryKey: dashboardQueryKey });
}
