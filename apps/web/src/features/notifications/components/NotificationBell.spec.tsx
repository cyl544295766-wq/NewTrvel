import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { NotificationBell } from './NotificationBell';

vi.mock('../hooks/useNotifications', () => ({
  useUnreadNotificationCount: () => ({ data: { count: 6 } }),
  useNotifications: () => ({ data: { notifications: [] }, isLoading: false }),
  useMarkNotificationRead: () => ({ isPending: false, mutate: vi.fn() }),
  useMarkAllNotificationsRead: () => ({ isPending: false, mutate: vi.fn() }),
}));

describe('NotificationBell', () => {
  it('shows the unread notification count', () => {
    render(
      <MemoryRouter>
        <NotificationBell />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: '通知，6 条未读' })).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
  });
});
