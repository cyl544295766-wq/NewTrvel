import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Photo } from '../types/photo.types';
import { PhotoGrid } from './PhotoGrid';

const createPhoto = (overrides: Partial<Photo> = {}): Photo => ({
  id: 'photo-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  url: 'data:image/jpeg;base64,YQ==',
  caption: '海边日落',
  shotAt: null,
  isCover: false,
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: null,
  tripPlace: null,
  ...overrides,
});

describe('PhotoGrid', () => {
  it('shows the empty state when there are no photos', () => {
    render(<PhotoGrid photos={[]} onSelect={vi.fn()} />);

    expect(screen.getByText('还没有照片，上传第一张旅行照片吧')).toBeInTheDocument();
  });

  it('sorts groups and photos by shot time or creation time descending', () => {
    const datedPhoto = createPhoto({
      id: 'photo-2',
      caption: '城市夜景',
      shotAt: '2026-07-13T12:00:00.000Z',
    });
    const newestDayPhoto = createPhoto({
      id: 'photo-3',
      caption: '清晨山景',
      shotAt: '2026-07-14T06:00:00.000Z',
      tripDayId: 'day-1',
      tripDay: { id: 'day-1', dayIndex: 1, date: '2026-07-10T00:00:00.000Z' },
    });
    const olderDayPhoto = createPhoto({
      id: 'photo-4',
      caption: '午后街道',
      shotAt: '2026-07-10T14:00:00.000Z',
      tripDayId: 'day-1',
      tripDay: { id: 'day-1', dayIndex: 1, date: '2026-07-10T00:00:00.000Z' },
    });
    const createdPhoto = createPhoto({
      id: 'photo-5',
      caption: '酒店窗景',
      createdAt: '2026-07-12T08:00:00.000Z',
      tripDayId: 'day-2',
      tripDay: { id: 'day-2', dayIndex: 2, date: '2026-07-12T00:00:00.000Z' },
    });

    render(
      <PhotoGrid
        photos={[olderDayPhoto, createdPhoto, datedPhoto, newestDayPhoto]}
        onSelect={vi.fn()}
      />,
    );

    const dateLabel = new Date(datedPhoto.shotAt!).toLocaleDateString('zh-CN');
    const headings = screen.getAllByRole('heading', { level: 2 });
    const dayGroup = screen.getByRole('heading', { name: '第 1 天' }).closest('section');

    expect(headings.map((heading) => heading.textContent)).toEqual([
      '第 1 天',
      dateLabel,
      '第 2 天',
    ]);
    expect(dayGroup).not.toBeNull();
    expect(
      within(dayGroup!)
        .getAllByRole('img')
        .map((image) => image.getAttribute('alt')),
    ).toEqual(['清晨山景', '午后街道']);
  });

  it('selects a photo from the grid', async () => {
    const onSelect = vi.fn();
    const photo = createPhoto();
    render(<PhotoGrid photos={[photo]} onSelect={onSelect} />);

    await userEvent.click(screen.getByRole('button', { name: '海边日落' }));

    expect(onSelect).toHaveBeenCalledWith(photo);
  });

  it('shows a cover label on the cover photo', () => {
    render(<PhotoGrid photos={[createPhoto({ isCover: true })]} onSelect={vi.fn()} />);

    expect(screen.getByText('封面')).toBeInTheDocument();
  });
});
