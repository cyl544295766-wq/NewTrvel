import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { Photo } from '../../photos/types/photo.types';
import { JournalForm } from './JournalForm';

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
        name: '浅草寺',
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

const photo: Photo = {
  id: 'photo-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  url: 'data:image/jpeg;base64,YQ==',
  caption: '浅草寺夜景',
  shotAt: null,
  isCover: false,
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: null,
  tripPlace: null,
};

describe('JournalForm', () => {
  it('validates title and submits journal fields with photos', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <JournalForm
        days={days}
        isSubmitting={false}
        onCancel={vi.fn()}
        onSubmit={onSubmit}
        photos={[photo]}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '创建游记' }));
    expect(screen.getByText('请填写游记标题')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('标题'), '东京第一天');
    await userEvent.selectOptions(screen.getByLabelText('心情'), 'excited');
    await userEvent.selectOptions(screen.getByLabelText('关联日期'), 'day-1');
    await userEvent.selectOptions(screen.getByLabelText('关联地点'), 'place-1');
    await userEvent.type(screen.getByLabelText('正文'), '## 夜晚\n浅草寺很安静。');
    await userEvent.selectOptions(screen.getByLabelText('发布状态'), 'published');
    await userEvent.click(screen.getByRole('checkbox', { name: /浅草寺夜景/ }));
    await userEvent.click(screen.getByRole('button', { name: '创建游记' }));

    expect(onSubmit).toHaveBeenCalledWith({
      title: '东京第一天',
      content: '## 夜晚\n浅草寺很安静。',
      tripDayId: 'day-1',
      tripPlaceId: 'place-1',
      mood: 'excited',
      isDraft: false,
      photoIds: ['photo-1'],
    });
  });
});
