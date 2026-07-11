import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MapPage } from './MapPage';

const mocks = vi.hoisted(() => ({ useTripMap: vi.fn(), refetch: vi.fn() }));

vi.mock('../hooks/useTripMap', () => ({ useTripMap: mocks.useTripMap }));
vi.mock('../components/TripMap', async (importOriginal) => {
  const original = await importOriginal<typeof import('../components/TripMap')>();
  return {
    ...original,
    TripMap: ({ route }: { route: Array<{ places: unknown[] }> }) => <div data-testid="trip-map">{route.flatMap((day) => day.places).length} visible places</div>,
  };
});

function renderPage() {
  return render(<MemoryRouter initialEntries={['/trips/trip-1/map']}><Routes><Route path="/trips/:tripId/map" element={<MapPage />} /></Routes></MemoryRouter>);
}

describe('MapPage', () => {
  beforeEach(() => {
    mocks.refetch.mockReset();
    mocks.useTripMap.mockReturnValue({
      data: { route: [{ dayId: 'day-1', dayIndex: 1, date: '2026-04-01', places: [{ id: 'place-1', name: '清水寺', type: 'attraction', latitude: '35', longitude: '135', sortOrder: 0 }] }] },
      isError: false, isLoading: false, refetch: mocks.refetch,
    });
  });

  it('renders the route workspace with all places by default', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: '旅行地图' })).toBeInTheDocument();
    expect(screen.getByTestId('trip-map')).toHaveTextContent('1 visible places');
    expect(screen.getByText('清水寺')).toBeInTheDocument();
  });

  it('shows the loading workspace skeleton', () => {
    mocks.useTripMap.mockReturnValue({ data: undefined, isError: false, isLoading: true, refetch: mocks.refetch });
    renderPage();
    expect(screen.getByLabelText('地图加载中')).toBeInTheDocument();
  });

  it('allows retrying after a loading error', async () => {
    mocks.useTripMap.mockReturnValue({ data: undefined, isError: true, isLoading: false, refetch: mocks.refetch });
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /重新加载/ }));
    expect(mocks.refetch).toHaveBeenCalledOnce();
  });
});
