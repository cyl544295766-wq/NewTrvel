import {
  Bell,
  CalendarClock,
  Check,
  FileWarning,
  PackageCheck,
  UserPlus,
  UserRoundCog,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { Notification, NotificationType } from '../types/notification.types';

type NotificationItemProps = {
  notification: Notification;
  compact?: boolean;
  isMarkingRead?: boolean;
  onMarkRead: (notificationId: string) => void;
};

const typeIcons = {
  trip_upcoming: CalendarClock,
  document_expiring: FileWarning,
  packing_pending: PackageCheck,
  member_invited: UserPlus,
  role_changed: UserRoundCog,
  system: Bell,
} satisfies Record<NotificationType, typeof Bell>;

export function NotificationItem({
  notification,
  compact = false,
  isMarkingRead = false,
  onMarkRead,
}: NotificationItemProps) {
  const Icon = typeIcons[notification.type];
  const className = [
    'notification-item',
    compact ? 'compact' : '',
    notification.isRead ? 'read' : 'unread',
    notification.priority === 'high' ? 'high-priority' : '',
  ]
    .filter(Boolean)
    .join(' ');

  const content = (
    <>
      <span className="notification-type-icon" aria-hidden="true">
        <Icon size={18} strokeWidth={2} />
      </span>
      <span className="notification-copy">
        <strong>{notification.title}</strong>
        <span>{notification.content}</span>
        <time>{formatNotificationTime(notification.createdAt)}</time>
      </span>
    </>
  );

  return (
    <article className={className}>
      {notification.link ? (
        <Link
          className="notification-main"
          onClick={() => {
            if (!notification.isRead) onMarkRead(notification.id);
          }}
          to={notification.link}
        >
          {content}
        </Link>
      ) : (
        <div className="notification-main">{content}</div>
      )}
      {!notification.isRead ? (
        <button
          aria-label={`标记「${notification.title}」为已读`}
          className="notification-read-button"
          disabled={isMarkingRead}
          onClick={() => onMarkRead(notification.id)}
          title="标记已读"
          type="button"
        >
          <Check size={15} />
        </button>
      ) : null}
    </article>
  );
}

function formatNotificationTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
