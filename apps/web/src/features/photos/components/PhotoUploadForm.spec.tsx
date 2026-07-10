import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PhotoUploadForm } from './PhotoUploadForm';

describe('PhotoUploadForm', () => {
  it('requires at least one file', async () => {
    const onSubmit = vi.fn();
    render(<PhotoUploadForm days={[]} isSubmitting={false} onSubmit={onSubmit} />);

    await userEvent.click(screen.getByRole('button', { name: '上传照片' }));

    expect(screen.getByText('请选择要上传的照片')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('rejects an unsupported file type', async () => {
    const onSubmit = vi.fn();
    render(<PhotoUploadForm days={[]} isSubmitting={false} onSubmit={onSubmit} />);
    const input = screen.getByLabelText('选择照片');
    const file = new File(['gif'], 'photo.gif', { type: 'image/gif' });

    fireEvent.change(input, { target: { files: [file] } });
    await userEvent.click(screen.getByRole('button', { name: '上传照片' }));

    expect(screen.getByText('仅支持 jpg、png、webp，单张图片不能超过 5MB')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('rejects a file larger than 5 MB', async () => {
    const onSubmit = vi.fn();
    render(<PhotoUploadForm days={[]} isSubmitting={false} onSubmit={onSubmit} />);
    const input = screen.getByLabelText('选择照片');
    const file = new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.jpg', {
      type: 'image/jpeg',
    });

    fireEvent.change(input, { target: { files: [file] } });
    await userEvent.click(screen.getByRole('button', { name: '上传照片' }));

    expect(screen.getByText('仅支持 jpg、png、webp，单张图片不能超过 5MB')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits a supported photo', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<PhotoUploadForm days={[]} isSubmitting={false} onSubmit={onSubmit} />);
    const file = new File(['photo'], 'photo.webp', { type: 'image/webp' });

    await userEvent.upload(screen.getByLabelText('选择照片'), file);
    await userEvent.type(screen.getByLabelText('备注'), '山顶合影');
    await userEvent.click(screen.getByRole('button', { name: '上传照片' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith([
      expect.objectContaining({
        caption: '山顶合影',
        isCover: true,
        url: expect.stringMatching(/^data:image\/webp;base64,/),
      }),
    ]);
  });
});
