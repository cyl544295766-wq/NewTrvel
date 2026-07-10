import { Bell } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from '../hooks/useNotifications';
import { NotificationPopover } from './NotificationPopover';

export function NotificationBell() {
  const [isOpen, setIsOpen] = useState(false);
  const shellRef = useRef<HTMLDivElement>(null);
  const unread = useUnreadNotificationCount();
  const notifications = useNotifications(1, 5, isOpen);
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();
  const unreadCount = unread.data?.count ?? 0;

  useEffect(() => {
    if (!isOpen) return;
    function handlePointerDown(event: MouseEvent) {
      if (!shellRef.current?.contains(event.target as Node)) setIsOpen(false);
    }
    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, [isOpen]);

  return (
    <div className="notification-bell-shell" ref={shellRef}>
      <button
        aria-expanded={isOpen}
        aria-label={unreadCount > 0 ? `通知，${unreadCount} 条未读` : '通知'}
        className="header-icon-button notification-bell"
        onClick={() => setIsOpen((current) => !current)}
        title="通知"
        type="button"
      >
        <Bell size={18} />
        {unreadCount > 0 ? <span>{unreadCount > 99 ? '99+' : unreadCount}</span> : null}
      </button>
      {isOpen ? (
        <NotificationPopover
          isLoading={notifications.isLoading}
          isMarkingRead={markRead.isPending}
          notifications={notifications.data?.notifications ?? []}
          onMarkAllRead={() => markAllRead.mutate()}
          onMarkRead={(notificationId) => markRead.mutate(notificationId)}
          unreadCount={unreadCount}
        />
      ) : null}
    </div>
  );
}
