import { CheckCheck, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { NotificationList } from '../components/NotificationList';
import {
  useClearReadNotifications,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from '../hooks/useNotifications';

export function NotificationsPage() {
  const [page, setPage] = useState(1);
  const notifications = useNotifications(page, 20);
  const unread = useUnreadNotificationCount();
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();
  const clearRead = useClearReadNotifications();
  const data = notifications.data;

  return (
    <main className="app-page notification-page">
      <Link className="text-link" to="/">
        返回首页
      </Link>
      <header className="top-bar notification-page-heading">
        <div>
          <p className="eyebrow">消息中心</p>
          <h1>通知</h1>
          <p>{unread.data?.count ? `${unread.data.count} 条未读通知` : '所有通知均已处理'}</p>
        </div>
        <div className="top-actions">
          <button
            className="secondary-button"
            disabled={!unread.data?.count || markAllRead.isPending}
            onClick={() => markAllRead.mutate()}
            type="button"
          >
            <CheckCheck size={17} />
            全部已读
          </button>
          <button
            className="secondary-button danger-button"
            disabled={clearRead.isPending}
            onClick={() => clearRead.mutate()}
            type="button"
          >
            <Trash2 size={17} />
            清空已读
          </button>
        </div>
      </header>

      <section className="notification-page-panel">
        {notifications.isLoading ? <p>加载中...</p> : null}
        {notifications.isError ? <p className="form-error">通知加载失败</p> : null}
        {data ? (
          <NotificationList
            isMarkingRead={markRead.isPending}
            notifications={data.notifications}
            onMarkRead={(notificationId) => markRead.mutate(notificationId)}
          />
        ) : null}
        {data && data.totalPages > 1 ? (
          <div className="notification-pagination">
            <button
              className="secondary-button"
              disabled={page <= 1}
              onClick={() => setPage((current) => current - 1)}
              type="button"
            >
              上一页
            </button>
            <span>
              第 {data.page} / {data.totalPages} 页
            </span>
            <button
              className="secondary-button"
              disabled={page >= data.totalPages}
              onClick={() => setPage((current) => current + 1)}
              type="button"
            >
              下一页
            </button>
          </div>
        ) : null}
      </section>
    </main>
  );
}
