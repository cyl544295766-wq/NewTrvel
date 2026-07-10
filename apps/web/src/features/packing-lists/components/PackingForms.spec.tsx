import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PackingItemForm } from './PackingItemForm';
import { PackingListForm } from './PackingListForm';

describe('PackingListForm', () => {
  it('submits a new list', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<PackingListForm isSubmitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await userEvent.type(screen.getByLabelText('清单名称'), '东京随身物品');
    await userEvent.selectOptions(screen.getByLabelText('分类'), 'electronics');
    await userEvent.click(screen.getByRole('button', { name: '创建清单' }));

    expect(onSubmit).toHaveBeenCalledWith({ name: '东京随身物品', category: 'electronics' });
  });
});

describe('PackingItemForm', () => {
  it('validates and submits an item', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<PackingItemForm isSubmitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await userEvent.click(screen.getByRole('button', { name: '添加物品' }));
    expect(screen.getByText('请填写物品名称')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('物品名称'), '数据线');
    await userEvent.clear(screen.getByLabelText('数量'));
    await userEvent.type(screen.getByLabelText('数量'), '2');
    await userEvent.type(screen.getByLabelText('备注'), '备用一条');
    await userEvent.click(screen.getByRole('button', { name: '添加物品' }));

    expect(onSubmit).toHaveBeenCalledWith({
      name: '数据线',
      quantity: 2,
      notes: '备用一条',
      orderIndex: undefined,
    });
  });
});
