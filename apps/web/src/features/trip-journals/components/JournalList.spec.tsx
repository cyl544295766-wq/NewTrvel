import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { TripJournal } from '../types/trip-journal.types';
import { JournalList } from './JournalList';

const journal: TripJournal = {
  id: 'journal-1',
  tripId: 'trip-1',
  tripDayId: 'day-1',
  tripPlaceId: null,
  title: '抵达东京',
  content: '# 第一天\n顺利抵达东京，晚上去了浅草寺。',
  isDraft: false,
  mood: 'excited',
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  tripDay: { id: 'day-1', dayIndex: 1, date: '2026-07-10T00:00:00.000Z' },
  tripPlace: null,
  photos: [],
};

describe('JournalList', () => {
  it('shows the empty state', () => {
    render(
      <MemoryRouter>
        <JournalList journals={[]} tripId="trip-1" />
      </MemoryRouter>,
    );

    expect(screen.getByText('还没有游记，写下旅途中的第一段记录吧')).toBeInTheDocument();
  });

  it('shows journal summary and links to details', () => {
    render(
      <MemoryRouter>
        <JournalList journals={[journal]} tripId="trip-1" />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /抵达东京/ })).toHaveAttribute(
      'href',
      '/trips/trip-1/journals/journal-1',
    );
    expect(screen.getByText('兴奋')).toBeInTheDocument();
    expect(screen.getByText(/顺利抵达东京/)).toBeInTheDocument();
  });
});
