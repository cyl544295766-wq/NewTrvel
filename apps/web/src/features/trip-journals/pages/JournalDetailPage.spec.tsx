import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { TripJournal } from '../types/trip-journal.types';
import { JournalDetailPage } from './JournalDetailPage';

const journal: TripJournal = {
  id: 'journal-1',
  tripId: 'trip-1',
  tripDayId: null,
  tripPlaceId: null,
  title: '东京第一天',
  content: '# 抵达\n顺利入住酒店。',
  isDraft: true,
  mood: 'happy',
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: null,
  tripPlace: null,
  photos: [],
};

const mocks = vi.hoisted(() => ({
  update: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('../../itinerary/hooks/useItinerary', () => ({
  useTripDays: vi.fn(() => ({ data: { days: [] } })),
}));

vi.mock('../../photos/hooks/usePhotos', () => ({
  usePhotos: vi.fn(() => ({ data: { photos: [] } })),
}));

vi.mock('../hooks/useTripJournals', () => ({
  useTripJournal: vi.fn(() => ({ data: { journal }, isLoading: false, isError: false })),
  useUpdateTripJournal: vi.fn(() => ({ isPending: false, mutateAsync: mocks.update })),
  useDeleteTripJournal: vi.fn(() => ({ isPending: false, mutateAsync: mocks.delete })),
}));

describe('JournalDetailPage', () => {
  it('publishes a draft and opens the edit form', async () => {
    mocks.update.mockResolvedValue({ journal: { ...journal, isDraft: false } });
    render(
      <MemoryRouter initialEntries={['/trips/trip-1/journals/journal-1']}>
        <Routes>
          <Route path="/trips/:tripId/journals/:journalId" element={<JournalDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText(/顺利入住酒店/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '发布' }));
    expect(mocks.update).toHaveBeenCalledWith({
      journalId: 'journal-1',
      input: { isDraft: false },
    });

    await userEvent.click(screen.getByRole('button', { name: '编辑' }));
    expect(screen.getByRole('button', { name: '保存游记' })).toBeInTheDocument();
  });

  it('shows a Chinese delete confirmation', async () => {
    render(
      <MemoryRouter initialEntries={['/trips/trip-1/journals/journal-1']}>
        <Routes>
          <Route path="/trips/:tripId/journals/:journalId" element={<JournalDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByRole('button', { name: '删除' }));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消' })).toBeInTheDocument();
  });
});
