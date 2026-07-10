import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { DocumentUploadForm } from './DocumentUploadForm';

const days: TripDay[] = [
  {
    id: 'day-1',
    tripId: 'trip-1',
    date: '2026-07-10T00:00:00.000Z',
    dayIndex: 1,
    title: null,
    summary: null,
    places: [
      {
        id: 'place-1',
        tripId: 'trip-1',
        tripDayId: 'day-1',
        name: '机场',
        type: 'transport',
        address: null,
        latitude: null,
        longitude: null,
        startTime: null,
        endTime: null,
        notes: null,
        sortOrder: 0,
        isCompleted: false,
      },
    ],
  },
];

describe('DocumentUploadForm', () => {
  it('requires a file', async () => {
    const onSubmit = vi.fn();
    render(<DocumentUploadForm days={days} isSubmitting={false} onSubmit={onSubmit} />);

    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(screen.getByText('请选择要上传的文档')).toBeInTheDocument();
  });

  it('rejects unsupported files', async () => {
    render(<DocumentUploadForm days={days} isSubmitting={false} onSubmit={vi.fn()} />);
    const file = new File(['zip'], 'archive.zip', { type: 'application/zip' });

    fireEvent.change(screen.getByLabelText('选择文件'), { target: { files: [file] } });
    await userEvent.type(screen.getByLabelText('标题'), '材料包');
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(screen.getByText('仅支持 PDF、jpg、png、webp 文件')).toBeInTheDocument();
  });

  it('rejects files larger than 10 MB', async () => {
    render(<DocumentUploadForm days={days} isSubmitting={false} onSubmit={vi.fn()} />);
    const file = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.pdf', {
      type: 'application/pdf',
    });

    fireEvent.change(screen.getByLabelText('选择文件'), { target: { files: [file] } });
    await userEvent.type(screen.getByLabelText('标题'), '保险材料');
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    expect(screen.getByText('单个文档不能超过 10MB')).toBeInTheDocument();
  });

  it('submits document metadata and relations', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<DocumentUploadForm days={days} isSubmitting={false} onSubmit={onSubmit} />);
    const file = new File(['pdf'], 'visa.pdf', { type: 'application/pdf' });

    await userEvent.upload(screen.getByLabelText('选择文件'), file);
    await userEvent.selectOptions(screen.getByLabelText('文档类型'), 'visa');
    await userEvent.type(screen.getByLabelText('标题'), '日本电子签证');
    await userEvent.type(screen.getByLabelText('备注'), '入境时出示');
    await userEvent.type(screen.getByLabelText('过期日期'), '2026-08-01');
    await userEvent.selectOptions(screen.getByLabelText('关联日期'), 'day-1');
    await userEvent.selectOptions(screen.getByLabelText('关联地点'), 'place-1');
    await userEvent.click(screen.getByRole('checkbox', { name: '到期前在仪表盘提醒' }));
    await userEvent.click(screen.getByRole('button', { name: '上传文档' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'visa',
        title: '日本电子签证',
        notes: '入境时出示',
        tripDayId: 'day-1',
        tripPlaceId: 'place-1',
        isReminder: true,
        url: expect.stringMatching(/^data:application\/pdf;base64,/),
      }),
    );
  });
});
