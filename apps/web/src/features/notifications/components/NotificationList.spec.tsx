import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Notification } from '../types/notification.types';
import { NotificationList } from './NotificationList';

describe('NotificationList', () => {
  it('renders notification titles and content', () => {
    const notifications: Notification[] = [
      {
        id: 'notification-1',
        userId: 'user-1',
        type: 'document_expiring',
        title: '旅行文档即将过期',
        content: '文档「旅行保险」将在 10 天后过期。',
        link: '/trips/trip-1/documents',
        metadata: null,
        isRead: false,
        priority: 'high',
        readAt: null,
        createdAt: '2026-07-11T00:00:00.000Z',
        expiresAt: null,
      },
      {
        id: 'notification-2',
        userId: 'user-1',
        type: 'packing_pending',
        title: '打包清单尚未完成',
        content: '还有 3 件物品未打包。',
        link: null,
        metadata: null,
        isRead: true,
        priority: 'normal',
        readAt: '2026-07-11T01:00:00.000Z',
        createdAt: '2026-07-10T00:00:00.000Z',
        expiresAt: null,
      },
    ];

    render(
      <MemoryRouter>
        <NotificationList notifications={notifications} onMarkRead={vi.fn()} />
      </MemoryRouter>,
    );

    expect(screen.getByText('旅行文档即将过期')).toBeInTheDocument();
    expect(screen.getByText('打包清单尚未完成')).toBeInTheDocument();
    expect(screen.getByText('文档「旅行保险」将在 10 天后过期。')).toBeInTheDocument();
  });
});
