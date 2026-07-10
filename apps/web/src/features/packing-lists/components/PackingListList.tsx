import { PackingItemInput, PackingList, PackingListInput } from '../types/packing-list.types';
import { PackingListCard } from './PackingListCard';

type Props = {
  isSaving: boolean;
  lists: PackingList[];
  onCreateItem: (listId: string, input: PackingItemInput) => Promise<void>;
  onDelete: (listId: string) => Promise<void>;
  onDeleteItem: (listId: string, itemId: string) => Promise<void>;
  onDuplicate: (listId: string) => Promise<void>;
  onToggleItem: (listId: string, itemId: string, isPacked: boolean) => Promise<void>;
  onUpdate: (listId: string, input: PackingListInput) => Promise<void>;
  onUpdateItem: (listId: string, itemId: string, input: PackingItemInput) => Promise<void>;
};

export function PackingListList({ lists, ...actions }: Props) {
  if (lists.length === 0) {
    return <p className="empty-state">还没有打包清单，创建一份开始准备吧</p>;
  }

  return (
    <div className="packing-list-list">
      {lists.map((list) => (
        <PackingListCard key={list.id} list={list} {...actions} />
      ))}
    </div>
  );
}
