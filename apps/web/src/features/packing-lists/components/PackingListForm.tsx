import { FormEvent, useState } from 'react';
import {
  PackingList,
  PackingListCategory,
  PackingListInput,
  packingListCategories,
  packingListCategoryLabels,
} from '../types/packing-list.types';

type Props = {
  initialList?: PackingList;
  isSubmitting: boolean;
  onCancel: () => void;
  onSubmit: (input: PackingListInput) => Promise<void>;
};

export function PackingListForm({ initialList, isSubmitting, onCancel, onSubmit }: Props) {
  const [name, setName] = useState(initialList?.name ?? '');
  const [category, setCategory] = useState<PackingListCategory>(initialList?.category ?? 'other');
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedName = name.trim();
    if (!normalizedName) {
      setError('请填写清单名称');
      return;
    }
    await onSubmit({ name: normalizedName, category });
  }

  return (
    <form className="trip-form packing-list-form" onSubmit={handleSubmit}>
      <div className="form-grid">
        <label>
          <span>清单名称</span>
          <input
            maxLength={100}
            onChange={(event) => setName(event.target.value)}
            placeholder="例如：出发前随身物品"
            value={name}
          />
        </label>
        <label>
          <span>分类</span>
          <select
            value={category}
            onChange={(event) => setCategory(event.target.value as PackingListCategory)}
          >
            {packingListCategories.map((item) => (
              <option key={item} value={item}>
                {packingListCategoryLabels[item]}
              </option>
            ))}
          </select>
        </label>
      </div>
      {error ? <p className="form-error">{error}</p> : null}
      <div className="form-actions">
        <button disabled={isSubmitting} type="submit">
          {isSubmitting ? '保存中...' : initialList ? '保存清单' : '创建清单'}
        </button>
        <button className="secondary-button" onClick={onCancel} type="button">
          取消
        </button>
      </div>
    </form>
  );
}
