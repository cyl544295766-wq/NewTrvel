import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PhotosPage } from './PhotosPage';

const mocks = vi.hoisted(() => ({
  useTripDays: vi.fn(),
  usePhotos: vi.fn(),
  useCreatePhoto: vi.fn(),
  useUpdatePhoto: vi.fn(),
  useDeletePhoto: vi.fn(),
}));

vi.mock('../../itinerary/hooks/useItinerary', () => ({
  useTripDays: mocks.useTripDays,
}));

vi.mock('../hooks/usePhotos', () => ({
  usePhotos: mocks.usePhotos,
  useCreatePhoto: mocks.useCreatePhoto,
  useUpdatePhoto: mocks.useUpdatePhoto,
  useDeletePhoto: mocks.useDeletePhoto,
}));

function renderPage(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/" element={<h1>首页</h1>} />
        <Route path="/photos" element={<PhotosPage />} />
        <Route path="/trips/:tripId/photos" element={<PhotosPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('PhotosPage', () => {
  beforeEach(() => {
    mocks.useTripDays.mockReturnValue({ data: { days: [] } });
    mocks.usePhotos.mockReturnValue({ data: { photos: [] }, isError: false, isLoading: false });
    mocks.useCreatePhoto.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
    mocks.useUpdatePhoto.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
    mocks.useDeletePhoto.mockReturnValue({ isPending: false, mutateAsync: vi.fn() });
  });

  it('redirects home when the trip route parameter is missing', () => {
    renderPage('/photos');

    expect(screen.getByRole('heading', { name: '首页' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '照片中心' })).not.toBeInTheDocument();
  });

  it('shows the loading state while photos are loading', () => {
    mocks.usePhotos.mockReturnValue({ data: undefined, isError: false, isLoading: true });

    renderPage('/trips/trip-1/photos');

    expect(screen.getByText('加载中...')).toBeInTheDocument();
  });

  it('shows the upload form after clicking the upload button', async () => {
    renderPage('/trips/trip-1/photos');

    await userEvent.click(screen.getByRole('button', { name: '上传照片' }));

    expect(screen.getByLabelText('选择照片')).toBeInTheDocument();
  });
});
