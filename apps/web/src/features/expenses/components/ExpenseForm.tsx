import { FormEvent, useEffect, useMemo, useState } from 'react';
import {
  Expense,
  ExpenseCategory,
  ExpenseInput,
  ExpenseShareInput,
  TripMember,
} from '../types/expense.types';

const categories: { value: ExpenseCategory; label: string }[] = [
  { value: 'transport', label: '油费/交通' },
  { value: 'hotel', label: '住宿' },
  { value: 'food', label: '餐饮' },
  { value: 'ticket', label: '门票' },
  { value: 'parking', label: '停车' },
  { value: 'shopping', label: '购物' },
  { value: 'maintenance', label: '维修' },
  { value: 'activity', label: '活动' },
  { value: 'other', label: '其他' },
];

type Props = {
  currentUserId?: string;
  initialExpense?: Expense | null;
  members: TripMember[];
  isSubmitting: boolean;
  onCancel: () => void;
  onSubmit: (input: ExpenseInput) => Promise<void>;
};

export function ExpenseForm({
  currentUserId,
  initialExpense,
  members,
  isSubmitting,
  onCancel,
  onSubmit,
}: Props) {
  const [title, setTitle] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('CNY');
  const [category, setCategory] = useState<ExpenseCategory>('other');
  const [payerUserId, setPayerUserId] = useState('');
  const [shares, setShares] = useState<ExpenseShareInput[]>([]);
  const [spentAt, setSpentAt] = useState(new Date().toISOString().slice(0, 10));
  const [notes, setNotes] = useState('');
  const [error, setError] = useState('');

  const memberOptions = useMemo(() => members.map((member) => member.userId), [members]);

  useEffect(() => {
    if (initialExpense) {
      setTitle(initialExpense.title);
      setAmount(initialExpense.amount);
      setCurrency(initialExpense.currency);
      setCategory(initialExpense.category);
      setPayerUserId(initialExpense.payer.id);
      setShares(
        members.map((member) => {
          const share = initialExpense.shares.find((item) => item.userId === member.userId);
          return { userId: member.userId, shareAmount: share?.shareAmount ?? '0.00' };
        }),
      );
      setSpentAt(new Date(initialExpense.spentAt).toISOString().slice(0, 10));
      setNotes(initialExpense.notes ?? '');
      setError('');
      return;
    }

    if (members.length === 0) return;
    const fallbackUserId =
      currentUserId && memberOptions.includes(currentUserId) ? currentUserId : members[0].userId;
    setPayerUserId((current) => current || fallbackUserId);
    setShares((current) =>
      current.length > 0
        ? current
        : members.map((member) => ({ userId: member.userId, shareAmount: '0.00' })),
    );
  }, [currentUserId, initialExpense, memberOptions, members]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const activeShares = shares.filter(
      (share) => Number(share.shareAmount) > 0 || share.userId === payerUserId,
    );
    const shareTotal = activeShares.reduce(
      (sum, share) => sum + Math.round(Number(share.shareAmount) * 100),
      0,
    );
    const amountCents = Math.round(Number(amount) * 100);

    if (activeShares.length === 0) {
      setError('分摊金额不能为空');
      return;
    }

    if (shareTotal !== amountCents) {
      setError('分摊金额之和必须等于消费总额');
      return;
    }

    setError('');
    await onSubmit({
      title,
      amount,
      currency,
      category,
      payerUserId,
      shares: activeShares,
      spentAt: new Date(`${spentAt}T00:00:00`).toISOString(),
      notes: notes || undefined,
    });
    setTitle('');
    setAmount('');
    setNotes('');
    setShares(members.map((member) => ({ userId: member.userId, shareAmount: '0.00' })));
    onCancel();
  }

  function updateShare(userId: string, shareAmount: string) {
    setShares((current) =>
      current.map((share) => (share.userId === userId ? { ...share, shareAmount } : share)),
    );
  }

  function autoSplit() {
    const amountCents = Math.round(Number(amount) * 100);
    if (!Number.isFinite(amountCents) || amountCents <= 0 || members.length === 0) {
      setError('请先填写消费总额');
      return;
    }

    const base = Math.floor(amountCents / members.length);
    let allocated = 0;
    setShares(
      members.map((member, index) => {
        const cents = index === members.length - 1 ? amountCents - allocated : base;
        allocated += cents;
        return { userId: member.userId, shareAmount: (cents / 100).toFixed(2) };
      }),
    );
    setError('');
  }

  return (
    <form className="trip-form" onSubmit={handleSubmit}>
      <div className="form-grid">
        <label>
          <span>标题</span>
          <input
            placeholder="例如：晚餐、加油、门票"
            required
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
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
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.displayName}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>支出日期</span>
          <input
            type="date"
            value={spentAt}
            onChange={(e) => setSpentAt(e.target.value)}
          />
        </label>
      </div>
      <fieldset className="checkbox-group expense-share-grid">
        <legend>参与人及分摊金额</legend>
        {members.map((member) => {
          const share = shares.find((item) => item.userId === member.userId);
          return (
            <label key={member.userId}>
              <span>{member.displayName}</span>
              <input
                min="0"
                step="0.01"
                type="number"
                value={share?.shareAmount ?? '0.00'}
                onChange={(e) => updateShare(member.userId, e.target.value)}
              />
            </label>
          );
        })}
      </fieldset>
      <button className="secondary-button" onClick={autoSplit} type="button">
        自动均摊
      </button>
      <label>
        <span>备注</span>
        <textarea
          placeholder="可填写消费说明"
          rows={3}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
        />
      </label>
      {error ? <p className="form-error">{error}</p> : null}
      <div className="form-actions">
        <button disabled={isSubmitting || members.length === 0} type="submit">
          {isSubmitting ? '保存中...' : '保存'}
        </button>
        <button className="secondary-button" onClick={onCancel} type="button">
          取消
        </button>
      </div>
    </form>
  );
}
