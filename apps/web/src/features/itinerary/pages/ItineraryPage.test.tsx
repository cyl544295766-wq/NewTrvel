import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ItineraryPage } from './ItineraryPage';

const mocks = vi.hoisted(() => ({
  useTrip: vi.fn(), useTripWeather: vi.fn(), useTripDays: vi.fn(), useGenerateTripDays: vi.fn(), useUpdateTripDay: vi.fn(), useCreateTripPlace: vi.fn(), useUpdateTripPlace: vi.fn(), useMoveTripPlace: vi.fn(), useReorderTripPlaces: vi.fn(), useDeleteTripPlace: vi.fn(),
}));

vi.mock('../../trips/hooks/useTrips', () => ({ useTrip: mocks.useTrip }));
vi.mock('../../weather', () => ({ useTripWeather: mocks.useTripWeather, WeatherBadge: () => <span>天气</span> }));
vi.mock('../hooks/useItinerary', () => ({ useTripDays: mocks.useTripDays, useGenerateTripDays: mocks.useGenerateTripDays, useUpdateTripDay: mocks.useUpdateTripDay, useCreateTripPlace: mocks.useCreateTripPlace, useUpdateTripPlace: mocks.useUpdateTripPlace, useMoveTripPlace: mocks.useMoveTripPlace, useReorderTripPlaces: mocks.useReorderTripPlaces, useDeleteTripPlace: mocks.useDeleteTripPlace }));
vi.mock('../../map/components/TripMap', () => ({ TripMap: () => <div data-testid="itinerary-mini-map" /> }));
vi.mock('../../trip-pdf', () => ({ ExportPdfButton: () => <button type="button">导出 PDF</button> }));
vi.mock('../../places/hooks/usePlaceSuggestions', () => ({ usePlaceSuggestions: () => ({ data: { suggestions: [] }, isError: false, isFetching: false }) }));

const trip = { id: 'trip-1', title: '西藏之旅', description: null, destination: '拉萨', startDate: '2026-07-11T00:00:00.000Z', endDate: '2026-07-13T00:00:00.000Z', status: 'planning', coverImageUrl: null, budget: null, ownerId: 'user-1', currentUserRole: 'owner', isFavorite: false, archivedAt: null, deletedAt: null, createdAt: '2026-07-01T00:00:00.000Z', updatedAt: '2026-07-01T00:00:00.000Z' } as const;
const days = [{ id: 'day-1', tripId: 'trip-1', date: '2026-07-11T00:00:00.000Z', dayIndex: 1, title: null, summary: null, places: [] }, { id: 'day-2', tripId: 'trip-1', date: '2026-07-12T00:00:00.000Z', dayIndex: 2, title: null, summary: null, places: [] }];

function renderPage() { return render(<MemoryRouter initialEntries={['/trips/trip-1/itinerary']}><Routes><Route path="/trips/:tripId/itinerary" element={<ItineraryPage />} /></Routes></MemoryRouter>); }

describe('ItineraryPage editorial layout', () => {
  beforeEach(() => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    HTMLElement.prototype.scrollIntoView = vi.fn();
    mocks.useTrip.mockReturnValue({ data: { trip }, isLoading: false, isError: false });
    mocks.useTripWeather.mockReturnValue({ data: { weather: [] }, isLoading: false });
    mocks.useTripDays.mockReturnValue({ data: { days }, isLoading: false });
    mocks.useGenerateTripDays.mockReturnValue({ isPending: false, mutate: vi.fn(), mutateAsync: vi.fn() });
    mocks.useUpdateTripDay.mockReturnValue({ mutateAsync: vi.fn() });
    mocks.useCreateTripPlace.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
    mocks.useUpdateTripPlace.mockReturnValue({ mutate: vi.fn() });
    mocks.useMoveTripPlace.mockReturnValue({ mutateAsync: vi.fn() });
    mocks.useReorderTripPlaces.mockReturnValue({ mutateAsync: vi.fn() });
    mocks.useDeleteTripPlace.mockReturnValue({ mutate: vi.fn() });
  });

  it('renders summary statistics and date navigation', () => {
    renderPage();
    expect(screen.getByRole('heading', { name: '行程日程' })).toBeInTheDocument();
    expect(screen.getByText('地点')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Day 1/ })).toBeInTheDocument();
    expect(screen.getAllByText('这一天还没有安排').length).toBe(2);
  });

  it('opens a day form from the primary add button', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getAllByRole('button', { name: '添加地点' })[0]);
    expect(screen.getByLabelText('搜索地点')).toBeInTheDocument();
  });

  it('generates dates when adding a place to a trip without days', async () => {
    const mutateAsync = vi.fn().mockResolvedValue({ days: [days[0]] });
    mocks.useTripDays.mockReturnValue({ data: { days: [] }, isLoading: false });
    mocks.useGenerateTripDays.mockReturnValue({ isPending: false, mutate: vi.fn(), mutateAsync });

    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getAllByRole('button', { name: '添加地点' })[0]);

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1));
  });
});
