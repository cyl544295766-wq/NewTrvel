import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadTripPdf } from '../api/trip-pdf.api';
import { ExportPdfButton } from './ExportPdfButton';

vi.mock('../api/trip-pdf.api', () => ({ downloadTripPdf: vi.fn() }));

describe('ExportPdfButton', () => {
  beforeEach(() => {
    vi.mocked(downloadTripPdf).mockReset();
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:trip-pdf'),
    });
    Object.defineProperty(window.URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });

  it('shows loading and success states while downloading the PDF', async () => {
    let resolveDownload: ((value: { blob: Blob; filename: string }) => void) | undefined;
    vi.mocked(downloadTripPdf).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveDownload = resolve;
        }),
    );
    const user = userEvent.setup();
    render(<ExportPdfButton tripId="trip-1" />);

    await user.click(screen.getByRole('button', { name: '导出 PDF' }));
    expect(screen.getByRole('button', { name: '生成中...' })).toBeDisabled();

    resolveDownload?.({ blob: new Blob(['pdf']), filename: '上海周末.pdf' });

    expect(await screen.findByRole('button', { name: '已下载' })).toBeEnabled();
    expect(downloadTripPdf).toHaveBeenCalledWith('trip-1');
    expect(window.URL.createObjectURL).toHaveBeenCalledOnce();
  });
});
