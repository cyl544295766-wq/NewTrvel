import { CheckCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Notification } from '../types/notification.types';
import { NotificationList } from './NotificationList';

type NotificationPopoverProps = {
  notifications: Notification[];
  isLoading: boolean;
  isMarkingRead: boolean;
  unreadCount: number;
  onMarkAllRead: () => void;
  onMarkRead: (notificationId: string) => void;
};

export function NotificationPopover({
  notifications,
  isLoading,
  isMarkingRead,
  unreadCount,
  onMarkAllRead,
  onMarkRead,
}: NotificationPopoverProps) {
  return (
    <section className="notification-popover" aria-label="通知列表">
      <header>
        <div>
          <strong>通知</strong>
          <span>{unreadCount > 0 ? `${unreadCount} 条未读` : '全部已读'}</span>
        </div>
        {unreadCount > 0 ? (
          <button className="notification-text-button" onClick={onMarkAllRead} type="button">
            <CheckCheck size={15} />
            全部已读
          </button>
        ) : null}
      </header>
      {isLoading ? (
        <p className="notification-loading">加载中...</p>
      ) : (
        <NotificationList
          compact
          isMarkingRead={isMarkingRead}
          notifications={notifications}
          onMarkRead={onMarkRead}
        />
      )}
      <footer>
        <Link to="/notifications">查看全部通知</Link>
      </footer>
    </section>
  );
}
