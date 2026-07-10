import { FormEvent, useState } from 'react';
import { PackingItem, PackingItemInput } from '../types/packing-list.types';

type Props = {
  initialItem?: PackingItem;
  isSubmitting: boolean;
  onCancel: () => void;
  onSubmit: (input: PackingItemInput) => Promise<void>;
};

export function PackingItemForm({ initialItem, isSubmitting, onCancel, onSubmit }: Props) {
  const [name, setName] = useState(initialItem?.name ?? '');
  const [quantity, setQuantity] = useState(String(initialItem?.quantity ?? 1));
  const [notes, setNotes] = useState(initialItem?.notes ?? '');
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedName = name.trim();
    const normalizedQuantity = Number(quantity);
    if (!normalizedName) {
      setError('请填写物品名称');
      return;
    }
    if (!Number.isInteger(normalizedQuantity) || normalizedQuantity < 1) {
      setError('物品数量必须大于等于 1');
      return;
    }
    await onSubmit({
      name: normalizedName,
      quantity: normalizedQuantity,
      notes: notes.trim() || undefined,
      orderIndex: initialItem?.orderIndex,
    });
  }

  return (
    <form className="trip-form packing-item-form" onSubmit={handleSubmit}>
      <div className="packing-item-form-grid">
        <label>
          <span>物品名称</span>
          <input maxLength={200} onChange={(event) => setName(event.target.value)} value={name} />
        </label>
        <label>
          <span>数量</span>
          <input
            min="1"
            onChange={(event) => setQuantity(event.target.value)}
            type="number"
            value={quantity}
          />
        </label>
        <label>
          <span>备注</span>
          <input maxLength={500} onChange={(event) => setNotes(event.target.value)} value={notes} />
        </label>
      </div>
      {error ? <p className="form-error">{error}</p> : null}
      <div className="form-actions">
        <button disabled={isSubmitting} type="submit">
          {isSubmitting ? '保存中...' : initialItem ? '保存物品' : '添加物品'}
        </button>
        <button className="secondary-button" onClick={onCancel} type="button">
          取消
        </button>
      </div>
    </form>
  );
}
