export type NotificationType =
  | 'trip_upcoming'
  | 'document_expiring'
  | 'packing_pending'
  | 'member_invited'
  | 'role_changed'
  | 'system';

export type NotificationPriority = 'low' | 'normal' | 'high';

export type Notification = {
  id: string;
  userId: string;
  type: NotificationType;
  title: string;
  content: string;
  link: string | null;
  metadata: Record<string, unknown> | null;
  isRead: boolean;
  priority: NotificationPriority;
  readAt: string | null;
  createdAt: string;
  expiresAt: string | null;
};

export type NotificationListResponse = {
  notifications: Notification[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
};
