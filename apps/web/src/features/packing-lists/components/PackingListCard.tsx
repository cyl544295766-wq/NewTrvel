import { useState } from 'react';
import {
  PackingItemInput,
  PackingList,
  PackingListInput,
  packingListCategoryLabels,
} from '../types/packing-list.types';
import { PackingItemForm } from './PackingItemForm';
import { PackingItemRow } from './PackingItemRow';
import { PackingListForm } from './PackingListForm';

type Props = {
  isSaving: boolean;
  list: PackingList;
  onCreateItem: (listId: string, input: PackingItemInput) => Promise<void>;
  onDelete: (listId: string) => Promise<void>;
  onDeleteItem: (listId: string, itemId: string) => Promise<void>;
  onDuplicate: (listId: string) => Promise<void>;
  onToggleItem: (listId: string, itemId: string, isPacked: boolean) => Promise<void>;
  onUpdate: (listId: string, input: PackingListInput) => Promise<void>;
  onUpdateItem: (listId: string, itemId: string, input: PackingItemInput) => Promise<void>;
};

export function PackingListCard({
  isSaving,
  list,
  onCreateItem,
  onDelete,
  onDeleteItem,
  onDuplicate,
  onToggleItem,
  onUpdate,
  onUpdateItem,
}: Props) {
  const [isExpanded, setIsExpanded] = useState(true);
  const [isEditing, setIsEditing] = useState(false);
  const [isAddingItem, setIsAddingItem] = useState(false);
  const progress = list.itemCount === 0 ? 0 : Math.round((list.packedCount / list.itemCount) * 100);

  return (
    <article className="packing-list-card">
      <header className="packing-list-header">
        <button
          aria-expanded={isExpanded}
          className="packing-list-toggle"
          onClick={() => setIsExpanded((current) => !current)}
          type="button"
        >
          <span>
            <strong>{list.name}</strong>
            <small>{packingListCategoryLabels[list.category]}</small>
          </span>
          <span>{isExpanded ? '收起' : '展开'}</span>
        </button>
        <div className="packing-list-progress-copy">
          <span>
            {list.packedCount} / {list.itemCount} 已打包
          </span>
          <strong>{progress}%</strong>
        </div>
        <progress aria-label={`${list.name}打包进度`} max={100} value={progress} />
      </header>

      {isExpanded ? (
        <div className="packing-list-body">
          <div className="packing-list-actions">
            <button
              className="secondary-button"
              onClick={() => setIsAddingItem(true)}
              type="button"
            >
              添加物品
            </button>
            <button className="secondary-button" onClick={() => setIsEditing(true)} type="button">
              编辑清单
            </button>
            <button
              className="secondary-button"
              disabled={isSaving}
              onClick={() => void onDuplicate(list.id)}
              type="button"
            >
              复制模板
            </button>
            <button
              className="secondary-button danger-button"
              disabled={isSaving}
              onClick={() => void onDelete(list.id)}
              type="button"
            >
              删除清单
            </button>
          </div>

          {isEditing ? (
            <PackingListForm
              initialList={list}
              isSubmitting={isSaving}
              onCancel={() => setIsEditing(false)}
              onSubmit={async (input) => {
                await onUpdate(list.id, input);
                setIsEditing(false);
              }}
            />
          ) : null}

          {isAddingItem ? (
            <PackingItemForm
              isSubmitting={isSaving}
              onCancel={() => setIsAddingItem(false)}
              onSubmit={async (input) => {
                await onCreateItem(list.id, input);
                setIsAddingItem(false);
              }}
            />
          ) : null}

          <div className="packing-items">
            {list.items.length === 0 ? (
              <p className="empty-state">清单中还没有物品</p>
            ) : (
              list.items.map((item) => (
                <PackingItemRow
                  isSaving={isSaving}
                  item={item}
                  key={item.id}
                  onDelete={(itemId) => onDeleteItem(list.id, itemId)}
                  onToggle={(itemId, isPacked) => onToggleItem(list.id, itemId, isPacked)}
                  onUpdate={(itemId, input) => onUpdateItem(list.id, itemId, input)}
                />
              ))
            )}
          </div>
        </div>
      ) : null}
    </article>
  );
}
