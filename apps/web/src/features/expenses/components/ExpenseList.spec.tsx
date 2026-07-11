import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ExpenseList } from './ExpenseList';

describe('ExpenseList', () => {
  it('renders a grouped ledger card with expense actions', () => {
    render(
      <ExpenseList
        canEdit
        expenses={[{
          id: 'expense-1',
          title: 'Museum ticket',
          amount: '120.00',
          currency: 'CNY',
          category: 'ticket',
          spentAt: new Date().toISOString(),
          notes: 'Afternoon visit',
          payer: { id: 'user-1', displayName: 'Alex' },
          shares: [{ userId: 'user-1', displayName: 'Alex', shareAmount: '120.00' }],
        }]}
        onDelete={vi.fn()}
        onEdit={vi.fn()}
      />,
    );

    expect(screen.getByText('Museum ticket')).toBeInTheDocument();
    expect(screen.getByText('门票')).toBeInTheDocument();
    expect(screen.getByText('120.00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '编辑Museum ticket' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '删除Museum ticket' })).toBeInTheDocument();
  });
});
