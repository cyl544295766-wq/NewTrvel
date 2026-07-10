import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { Photo } from '../types/photo.types';
import { PhotoDetailModal } from './PhotoDetailModal';

const photo: Photo = {
  id: 'photo-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  url: 'data:image/jpeg;base64,YQ==',
  caption: '旧备注',
  shotAt: null,
  isCover: false,
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
        name: '西湖',
        type: 'attraction',
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

function renderModal(overrides: Partial<Parameters<typeof PhotoDetailModal>[0]> = {}) {
  const props = {
    days,
    isDeleting: false,
    isSaving: false,
    onClose: vi.fn(),
    onDelete: vi.fn(),
    onSave: vi.fn().mockResolvedValue(undefined),
    photo,
    ...overrides,
  };
  render(<PhotoDetailModal {...props} />);
  return props;
}

describe('PhotoDetailModal', () => {
  it('shows the selected photo details', () => {
    renderModal();

    expect(screen.getByRole('heading', { name: '照片详情' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '旧备注' })).toHaveAttribute('src', photo.url);
    expect(screen.getByLabelText('备注')).toHaveValue('旧备注');
  });

  it('edits photo fields and sets the photo as cover', async () => {
    const { onSave } = renderModal();
    const shotAt = '2026-07-11T09:30';

    const captionInput = screen.getByLabelText('备注');
    await userEvent.clear(captionInput);
    await userEvent.type(captionInput, '新备注');
    await userEvent.type(screen.getByLabelText('拍摄时间'), shotAt);
    await userEvent.selectOptions(screen.getByLabelText('关联日期'), 'day-1');
    await userEvent.selectOptions(screen.getByLabelText('关联地点'), 'place-1');
    await userEvent.click(screen.getByRole('checkbox', { name: '设为封面' }));
    await userEvent.click(screen.getByRole('button', { name: '保存' }));

    expect(onSave).toHaveBeenCalledWith(
      photo.id,
      expect.objectContaining({
        caption: '新备注',
        shotAt: new Date(shotAt).toISOString(),
        tripDayId: 'day-1',
        tripPlaceId: 'place-1',
        isCover: true,
      }),
    );
  });

  it('deletes the selected photo', async () => {
    const { onDelete } = renderModal();

    await userEvent.click(screen.getByRole('button', { name: '删除照片' }));

    expect(onDelete).toHaveBeenCalledWith(photo.id);
  });
});
