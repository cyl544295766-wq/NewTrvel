import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Notification } from '../types/notification.types';
import { NotificationItem } from './NotificationItem';

describe('NotificationItem', () => {
  it('marks an unread notification as read', async () => {
    const onMarkRead = vi.fn();
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <NotificationItem notification={createNotification()} onMarkRead={onMarkRead} />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: '标记「行程即将开始」为已读' }));

    expect(onMarkRead).toHaveBeenCalledWith('notification-1');
  });
});

function createNotification(): Notification {
  return {
    id: 'notification-1',
    userId: 'user-1',
    type: 'trip_upcoming',
    title: '行程即将开始',
    content: '旅行「上海旅行」将在 3 天后出发。',
    link: '/trips/trip-1',
    metadata: { tripId: 'trip-1' },
    isRead: false,
    priority: 'normal',
    readAt: null,
    createdAt: '2026-07-11T00:00:00.000Z',
    expiresAt: null,
  };
}
