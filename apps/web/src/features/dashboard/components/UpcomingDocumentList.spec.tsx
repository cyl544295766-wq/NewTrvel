import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { UpcomingDocumentList } from './UpcomingDocumentList';

describe('UpcomingDocumentList', () => {
  it('shows an empty reminder state', () => {
    render(
      <MemoryRouter>
        <UpcomingDocumentList documents={[]} />
      </MemoryRouter>,
    );

    expect(screen.getByText('未来 30 天没有即将过期的文档')).toBeInTheDocument();
  });

  it('links an expiring document to its trip documents page', () => {
    render(
      <MemoryRouter>
        <UpcomingDocumentList
          documents={[
            {
              id: 'document-1',
              tripId: 'trip-1',
              tripTitle: '东京之旅',
              type: 'visa',
              title: '日本电子签证',
              expiredAt: '2026-08-01T00:00:00.000Z',
            },
          ]}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /日本电子签证/ })).toHaveAttribute(
      'href',
      '/trips/trip-1/documents',
    );
    expect(screen.getByText('签证 · 东京之旅')).toBeInTheDocument();
  });
});
