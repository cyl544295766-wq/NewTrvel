import { request } from '../../../services/http';
import { NotificationListResponse } from '../types/notification.types';

export function getNotifications(page = 1, pageSize = 20): Promise<NotificationListResponse> {
  return request<NotificationListResponse>(`/notifications?page=${page}&pageSize=${pageSize}`);
}

export function getUnreadNotificationCount(): Promise<{ count: number }> {
  return request<{ count: number }>('/notifications/unread-count');
}

export function markNotificationRead(notificationId: string): Promise<{ success: true }> {
  return request<{ success: true }>(`/notifications/${notificationId}/read`, { method: 'PATCH' });
}

export function markAllNotificationsRead(): Promise<{ success: true; count: number }> {
  return request<{ success: true; count: number }>('/notifications/read-all', { method: 'POST' });
}

export function clearReadNotifications(): Promise<{ success: true; count: number }> {
  return request<{ success: true; count: number }>('/notifications/clear-read', {
    method: 'DELETE',
  });
}
