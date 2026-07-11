import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripForm } from './TripForm';

describe('TripForm editorial mode', () => {
  it('validates each step inline before continuing', async () => {
    const user = userEvent.setup();
    render(<TripForm isSubmitting={false} mode="editorial" onSubmit={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /继续/ }));

    expect(screen.getByText('请输入这次旅行的目的地')).toBeInTheDocument();
    expect(screen.getByText('请为旅行写一个标题')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '从一个地方开始想象' })).toBeInTheDocument();
  });

  it('generates a title, calculates duration, and submits the completed plan', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<TripForm isSubmitting={false} mode="editorial" onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText('目的地'), '京都');
    expect(screen.getByLabelText('旅行标题')).toHaveValue('京都之旅');
    await user.type(screen.getByLabelText('旅行寄语'), '在春天看一场花雨。');
    await user.click(screen.getByRole('button', { name: /继续/ }));

    await user.type(screen.getByLabelText('出发日期'), '2026-04-01');
    await user.type(screen.getByLabelText('结束日期'), '2026-04-05');
    expect(screen.getByText('5')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /继续/ }));

    await user.click(screen.getByRole('button', { name: '选择封面 1' }));
    await user.type(screen.getByLabelText('旅行预算'), '12000');
    await user.click(screen.getByRole('button', { name: /继续/ }));
    await user.click(screen.getByRole('button', { name: /创建旅行/ }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '京都之旅',
        destination: '京都',
        description: '在春天看一场花雨。',
        startDate: '2026-04-01',
        endDate: '2026-04-05',
        budget: '12000',
        coverImageUrl: expect.any(String),
      }),
    );
    expect(decodeURIComponent(onSubmit.mock.calls[0][0].coverImageUrl)).toContain('京都-1');
  });
});
