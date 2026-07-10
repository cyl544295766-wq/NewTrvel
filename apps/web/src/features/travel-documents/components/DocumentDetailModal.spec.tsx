import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { TravelDocument } from '../types/travel-document.types';
import { DocumentDetailModal } from './DocumentDetailModal';

const document: TravelDocument = {
  id: 'document-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  type: 'passport',
  title: '护照首页',
  url: 'data:application/pdf;base64,YQ==',
  notes: '随身携带',
  expiredAt: null,
  isReminder: false,
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: null,
  tripPlace: null,
};

const days: TripDay[] = [
  {
    id: 'day-1',
    tripId: 'trip-1',
    date: '2026-07-10T00:00:00.000Z',
    dayIndex: 1,
    title: null,
    summary: null,
    places: [
      {
        id: 'place-1',
        tripId: 'trip-1',
        tripDayId: 'day-1',
        name: '领事馆',
        type: 'custom',
        address: null,
        latitude: null,
        longitude: null,
        startTime: null,
        endTime: null,
        notes: null,
        sortOrder: 0,
        isCompleted: false,
      },
    ],
  },
];

function renderModal() {
  const props = {
    days,
    document,
    isDeleting: false,
    isSaving: false,
    onClose: vi.fn(),
    onDelete: vi.fn(),
    onSave: vi.fn().mockResolvedValue(undefined),
  };
  render(<DocumentDetailModal {...props} />);
  return props;
}

describe('DocumentDetailModal', () => {
  it('shows and edits document details', async () => {
    const { onSave } = renderModal();

    expect(screen.getByRole('heading', { name: '文档详情' })).toBeInTheDocument();
    expect(screen.getByText('PDF 文档')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('文档类型'), 'visa');
    await userEvent.clear(screen.getByLabelText('标题'));
    await userEvent.type(screen.getByLabelText('标题'), '更新后的签证');
    await userEvent.clear(screen.getByLabelText('备注'));
    await userEvent.type(screen.getByLabelText('备注'), '打印两份');
    await userEvent.type(screen.getByLabelText('过期日期'), '2026-08-15');
    await userEvent.selectOptions(screen.getByLabelText('关联日期'), 'day-1');
    await userEvent.selectOptions(screen.getByLabelText('关联地点'), 'place-1');
    await userEvent.click(screen.getByRole('checkbox', { name: '到期前在仪表盘提醒' }));
    await userEvent.click(screen.getByRole('button', { name: '保存' }));

    expect(onSave).toHaveBeenCalledWith(
      document.id,
      expect.objectContaining({
        type: 'visa',
        title: '更新后的签证',
        notes: '打印两份',
        tripDayId: 'day-1',
        tripPlaceId: 'place-1',
        isReminder: true,
      }),
    );
  });

  it('deletes the selected document', async () => {
    const { onDelete } = renderModal();

    await userEvent.click(screen.getByRole('button', { name: '删除文档' }));

    expect(onDelete).toHaveBeenCalledWith(document.id);
  });
});
