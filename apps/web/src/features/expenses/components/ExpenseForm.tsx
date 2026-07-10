import { FormEvent, useEffect, useState } from 'react';
import { ExpenseCategory, ExpenseInput, TripMember } from '../types/expense.types';

const categories: { value: ExpenseCategory; label: string }[] = [
  { value: 'food', label: '餐饮' },
  { value: 'transport', label: '交通' },
  { value: 'hotel', label: '住宿' },
  { value: 'ticket', label: '门票' },
  { value: 'shopping', label: '购物' },
  { value: 'activity', label: '活动' },
  { value: 'other', label: '其他' },
];

type Props = {
  members: TripMember[];
  isSubmitting: boolean;
  onSubmit: (input: ExpenseInput) => Promise<void>;
};

export function ExpenseForm({ members, isSubmitting, onSubmit }: Props) {
  const [title, setTitle] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('CNY');
  const [category, setCategory] = useState<ExpenseCategory>('other');
  const [payerUserId, setPayerUserId] = useState('');
  const [shareUserIds, setShareUserIds] = useState<string[]>([]);
  const [spentAt, setSpentAt] = useState(new Date().toISOString().slice(0, 16));
  const [notes, setNotes] = useState('');

  useEffect(() => {
    if (members.length > 0 && !payerUserId) {
      setPayerUserId(members[0].userId);
      setShareUserIds(members.map((member) => member.userId));
    }
  }, [members, payerUserId]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSubmit({
      title,
      amount,
      currency,
      category,
      payerUserId,
      shareUserIds,
      spentAt: new Date(spentAt).toISOString(),
      notes: notes || undefined,
    });
    setTitle('');
    setAmount('');
    setNotes('');
  }

  function toggleShare(userId: string) {
    setShareUserIds((current) =>
      current.includes(userId) ? current.filter((id) => id !== userId) : [...current, userId],
    );
  }

  return (
    <form className="trip-form" onSubmit={handleSubmit}>
      <div className="form-grid">
        <label>
          <span>标题</span>
          <input required value={title} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label>
          <span>金额</span>
          <input
            min="0.01"
            step="0.01"
            type="number"
            required
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
      </div>
      <div className="form-grid">
        <label>
          <span>币种</span>
          <input value={currency} onChange={(e) => setCurrency(e.target.value)} />
        </label>
        <label>
          <span>分类</span>
          <select value={category} onChange={(e) => setCategory(e.target.value as ExpenseCategory)}>
            {categories.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="form-grid">
        <label>
          <span>付款人</span>
          <select value={payerUserId} onChange={(e) => setPayerUserId(e.target.value)}>
            {members.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.displayName}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>支出时间</span>
          <input
            type="datetime-local"
            value={spentAt}
            onChange={(e) => setSpentAt(e.target.value)}
          />
        </label>
      </div>
      <fieldset className="checkbox-group">
        <legend>分摊成员</legend>
        {members.map((m) => (
          <label key={m.userId}>
            <input
              checked={shareUserIds.includes(m.userId)}
              onChange={() => toggleShare(m.userId)}
              type="checkbox"
            />
            {m.displayName}
          </label>
        ))}
      </fieldset>
      <label>
        <span>备注</span>
        <textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      <button disabled={isSubmitting || shareUserIds.length === 0} type="submit">
        {isSubmitting ? '保存中...' : '新增费用'}
      </button>
    </form>
  );
}
