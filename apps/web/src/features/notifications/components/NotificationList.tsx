import { Notification } from '../types/notification.types';
import { NotificationItem } from './NotificationItem';

type NotificationListProps = {
  notifications: Notification[];
  compact?: boolean;
  isMarkingRead?: boolean;
  onMarkRead: (notificationId: string) => void;
};

export function NotificationList({
  notifications,
  compact = false,
  isMarkingRead = false,
  onMarkRead,
}: NotificationListProps) {
  if (notifications.length === 0) {
    return <p className="empty-state compact-empty">暂无通知</p>;
  }

  return (
    <div className={compact ? 'notification-list compact' : 'notification-list'}>
      {notifications.map((notification) => (
        <NotificationItem
          compact={compact}
          isMarkingRead={isMarkingRead}
          key={notification.id}
          notification={notification}
          onMarkRead={onMarkRead}
        />
      ))}
    </div>
  );
}
