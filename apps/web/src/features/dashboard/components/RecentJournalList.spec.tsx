import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { RecentJournalList } from './RecentJournalList';

describe('RecentJournalList', () => {
  it('links recent journal summaries to their detail pages', () => {
    render(
      <MemoryRouter>
        <RecentJournalList
          journals={[
            {
              id: 'journal-1',
              tripId: 'trip-1',
              tripTitle: '东京之旅',
              title: '抵达东京',
              summary: '顺利抵达东京，晚上去了浅草寺。',
              createdAt: '2026-07-10T08:00:00.000Z',
            },
          ]}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /抵达东京/ })).toHaveAttribute(
      'href',
      '/trips/trip-1/journals/journal-1',
    );
    expect(screen.getByText('顺利抵达东京，晚上去了浅草寺。')).toBeInTheDocument();
  });
});
