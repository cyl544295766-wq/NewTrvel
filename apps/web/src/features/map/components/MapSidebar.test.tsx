import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { MapSidebar } from './MapSidebar';
import { TripRouteDay } from '../types/map.types';

const route: TripRouteDay[] = [
  {
    dayId: 'day-1', dayIndex: 1, date: '2026-04-01',
    places: [
      { id: 'place-1', name: '清水寺', type: 'attraction', latitude: '35.0', longitude: '135.7', sortOrder: 0 },
      { id: 'place-2', name: '祇园酒店', type: 'hotel', latitude: '35.1', longitude: '135.8', sortOrder: 1 },
      { id: 'place-missing', name: '待定餐厅', type: 'restaurant', latitude: '', longitude: '', sortOrder: 2 },
    ],
  },
  {
    dayId: 'day-2', dayIndex: 2, date: '2026-04-02',
    places: [{ id: 'place-3', name: '岚山', type: 'attraction', latitude: '35.2', longitude: '135.6', sortOrder: 0 }],
  },
];

function renderSidebar(overrides: Partial<React.ComponentProps<typeof MapSidebar>> = {}) {
  const props: React.ComponentProps<typeof MapSidebar> = {
    activeDayId: null,
    activePlaceType: 'all',
    missingPlaceCount: 1,
    onDayChange: vi.fn(),
    onPlaceTypeChange: vi.fn(),
    onSelectPlace: vi.fn(),
    route,
    selectedPlaceId: null,
    tripId: 'trip-1',
    ...overrides,
  };
  render(<MemoryRouter><MapSidebar {...props} /></MemoryRouter>);
  return props;
}

describe('MapSidebar', () => {
  it('renders valid places and reports missing coordinates', () => {
    renderSidebar();
    expect(screen.getByText('清水寺')).toBeInTheDocument();
    expect(screen.getByText('岚山')).toBeInTheDocument();
    expect(screen.queryByText('待定餐厅')).not.toBeInTheDocument();
    expect(screen.getByText('1 个地点缺少坐标，暂未显示')).toBeInTheDocument();
  });

  it('changes the active day and place type', async () => {
    const user = userEvent.setup();
    const props = renderSidebar();
    await user.click(screen.getByRole('button', { name: /D2/ }));
    await user.click(screen.getByRole('button', { name: '酒店' }));
    expect(props.onDayChange).toHaveBeenCalledWith('day-2');
    expect(props.onPlaceTypeChange).toHaveBeenCalledWith('hotel');
  });

  it('selects a place from the route list', async () => {
    const user = userEvent.setup();
    const props = renderSidebar();
    await user.click(screen.getByRole('button', { name: /清水寺/ }));
    expect(props.onSelectPlace).toHaveBeenCalledWith('place-1');
  });
});
