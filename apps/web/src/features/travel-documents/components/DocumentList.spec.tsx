import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TravelDocument } from '../types/travel-document.types';
import { DocumentList } from './DocumentList';

const createDocument = (overrides: Partial<TravelDocument> = {}): TravelDocument => ({
  id: 'document-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  type: 'passport',
  title: '护照首页',
  url: 'data:application/pdf;base64,YQ==',
  notes: null,
  expiredAt: '2027-07-10T00:00:00.000Z',
  isReminder: true,
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: null,
  tripPlace: null,
  ...overrides,
});

describe('DocumentList', () => {
  it('shows the empty state', () => {
    render(<DocumentList documents={[]} onSelect={vi.fn()} />);

    expect(screen.getByText('还没有旅行文档，上传第一份证件或订单吧')).toBeInTheDocument();
  });

  it('groups documents by type and selects a document', async () => {
    const onSelect = vi.fn();
    const passport = createDocument();
    const ticket = createDocument({
      id: 'document-2',
      type: 'ticket',
      title: '博物馆门票',
      url: 'data:image/png;base64,YQ==',
      isReminder: false,
    });

    render(<DocumentList documents={[ticket, passport]} onSelect={onSelect} />);

    const passportGroup = screen.getByRole('heading', { name: '护照' }).closest('section');
    const ticketGroup = screen.getByRole('heading', { name: '门票' }).closest('section');
    expect(within(passportGroup!).getByText('护照首页')).toBeInTheDocument();
    expect(within(ticketGroup!).getByText('博物馆门票')).toBeInTheDocument();
    expect(screen.getByText('提醒')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /博物馆门票/ }));
    expect(onSelect).toHaveBeenCalledWith(ticket);
  });
});
