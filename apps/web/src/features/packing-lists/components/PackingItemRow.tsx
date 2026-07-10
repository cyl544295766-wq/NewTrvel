import { useState } from 'react';
import { PackingItem, PackingItemInput } from '../types/packing-list.types';
import { PackingItemForm } from './PackingItemForm';

type Props = {
  isSaving: boolean;
  item: PackingItem;
  onDelete: (itemId: string) => Promise<void>;
  onToggle: (itemId: string, isPacked: boolean) => Promise<void>;
  onUpdate: (itemId: string, input: PackingItemInput) => Promise<void>;
};

export function PackingItemRow({ isSaving, item, onDelete, onToggle, onUpdate }: Props) {
  const [isEditing, setIsEditing] = useState(false);

  if (isEditing) {
    return (
      <PackingItemForm
        initialItem={item}
        isSubmitting={isSaving}
        onCancel={() => setIsEditing(false)}
        onSubmit={async (input) => {
          await onUpdate(item.id, input);
          setIsEditing(false);
        }}
      />
    );
  }

  return (
    <div className={item.isPacked ? 'packing-item-row packed' : 'packing-item-row'}>
      <label className="packing-item-check">
        <input
          aria-label={`将${item.name}标记为${item.isPacked ? '未打包' : '已打包'}`}
          checked={item.isPacked}
          disabled={isSaving}
          onChange={(event) => void onToggle(item.id, event.target.checked)}
          type="checkbox"
        />
        <span>
          <strong>{item.name}</strong>
          {item.notes ? <small>{item.notes}</small> : null}
        </span>
      </label>
      <span className="packing-item-quantity">× {item.quantity}</span>
      <div className="packing-item-actions">
        <button className="secondary-button" onClick={() => setIsEditing(true)} type="button">
          编辑
        </button>
        <button
          className="secondary-button danger-button"
          disabled={isSaving}
          onClick={() => void onDelete(item.id)}
          type="button"
        >
          删除
        </button>
      </div>
    </div>
  );
}
