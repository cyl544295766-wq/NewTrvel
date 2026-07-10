import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ExpenseForm } from './ExpenseForm';

describe('ExpenseForm', () => {
  it('uses a date-only field and does not show receipt upload', () => {
    render(
      <ExpenseForm
        currentUserId="user-1"
        isSubmitting={false}
        members={[
          {
            userId: 'user-1',
            username: 'traveler',
            displayName: '旅行者',
            role: 'owner',
          },
        ]}
        onCancel={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('支出日期')).toHaveAttribute('type', 'date');
    expect(screen.queryByText('小票上传功能开发中')).not.toBeInTheDocument();
  });
});
