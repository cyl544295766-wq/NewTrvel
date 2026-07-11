import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TripPlaceForm } from './TripPlaceForm';

const mocks = vi.hoisted(() => ({ usePlaceSuggestions: vi.fn() }));
vi.mock('../../places/hooks/usePlaceSuggestions', () => ({ usePlaceSuggestions: mocks.usePlaceSuggestions }));

describe('TripPlaceForm', () => {
  beforeEach(() => {
    mocks.usePlaceSuggestions.mockReturnValue({
      data: { suggestions: [{ id: 'poi-1', name: '布达拉宫', address: '北京中路35号', district: '城关区', city: '拉萨', province: '西藏自治区', type: 'attraction', sourceType: '110200', latitude: '29.6427', longitude: '91.1153' }] },
      isError: false, isFetching: false,
    });
  });

  it('submits selected place metadata and coordinates', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<TripPlaceForm isSubmitting={false} onSubmit={onSubmit} searchCity="拉萨" tripDayId="day-1" />);
    await user.type(screen.getByLabelText('搜索地点'), '布达');
    await user.click(await screen.findByRole('option', { name: /布达拉宫/ }));
    await user.click(screen.getByRole('button', { name: '添加并在地图标记' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      tripDayId: 'day-1', name: '布达拉宫', type: 'attraction', address: '北京中路35号', latitude: 29.6427, longitude: 91.1153,
    }));
  });
});
