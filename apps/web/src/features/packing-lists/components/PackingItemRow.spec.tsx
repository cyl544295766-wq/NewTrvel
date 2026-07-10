import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PackingItem } from '../types/packing-list.types';
import { PackingItemRow } from './PackingItemRow';

const item: PackingItem = {
  id: 'item-1',
  packingListId: 'list-1',
  name: '充电器',
  quantity: 2,
  isPacked: false,
  notes: 'Type-C',
  orderIndex: 0,
};

describe('PackingItemRow', () => {
  it('toggles an item packed state', async () => {
    const onToggle = vi.fn().mockResolvedValue(undefined);
    render(
      <PackingItemRow
        isSaving={false}
        item={item}
        onDelete={vi.fn()}
        onToggle={onToggle}
        onUpdate={vi.fn()}
      />,
    );

    await userEvent.click(screen.getByRole('checkbox', { name: '将充电器标记为已打包' }));

    expect(onToggle).toHaveBeenCalledWith(item.id, true);
    expect(screen.getByText('× 2')).toBeInTheDocument();
    expect(screen.getByText('Type-C')).toBeInTheDocument();
  });
});
