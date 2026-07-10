import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PackingItem, PackingList } from '../types/packing-list.types';
import { PackingListList } from './PackingListList';

const item: PackingItem = {
  id: 'item-1',
  packingListId: 'list-1',
  name: '手机充电器',
  quantity: 1,
  isPacked: true,
  notes: '放入随身包',
  orderIndex: 0,
};

const list: PackingList = {
  id: 'list-1',
  tripId: 'trip-1',
  name: '电子设备',
  category: 'electronics',
  packedCount: 1,
  itemCount: 2,
  createdAt: '2026-07-10T08:00:00.000Z',
  updatedAt: '2026-07-10T08:00:00.000Z',
  items: [item, { ...item, id: 'item-2', name: '充电宝', isPacked: false, orderIndex: 1 }],
};

function renderList() {
  const props = {
    lists: [list],
    isSaving: false,
    onCreateItem: vi.fn(),
    onDelete: vi.fn(),
    onDeleteItem: vi.fn(),
    onDuplicate: vi.fn(),
    onToggleItem: vi.fn(),
    onUpdate: vi.fn(),
    onUpdateItem: vi.fn(),
  };
  render(<PackingListList {...props} />);
  return props;
}

describe('PackingListList', () => {
  it('shows an empty state', () => {
    render(<PackingListList {...renderListProps([])} />);

    expect(screen.getByText('还没有打包清单，创建一份开始准备吧')).toBeInTheDocument();
  });

  it('shows list progress and expanded items', () => {
    renderList();

    expect(screen.getByText('电子设备')).toBeInTheDocument();
    expect(screen.getByText('1 / 2 已打包')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: '电子设备打包进度' })).toHaveValue(50);
    expect(screen.getByText('手机充电器')).toBeInTheDocument();
    expect(screen.getByText('充电宝')).toBeInTheDocument();
  });

  it('collapses and expands a list', async () => {
    renderList();
    const toggle = screen.getByRole('button', { name: /电子设备/ });

    await userEvent.click(toggle);
    expect(screen.queryByText('手机充电器')).not.toBeInTheDocument();
    await userEvent.click(toggle);
    expect(screen.getByText('手机充电器')).toBeInTheDocument();
  });
});

function renderListProps(lists: PackingList[]) {
  return {
    lists,
    isSaving: false,
    onCreateItem: vi.fn(),
    onDelete: vi.fn(),
    onDeleteItem: vi.fn(),
    onDuplicate: vi.fn(),
    onToggleItem: vi.fn(),
    onUpdate: vi.fn(),
    onUpdateItem: vi.fn(),
  };
}
