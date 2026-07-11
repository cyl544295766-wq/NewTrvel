import {
  BedDouble,
  CircleParking,
  Edit3,
  MoreHorizontal,
  Plus,
  ReceiptText,
  ShoppingBag,
  Sparkles,
  Trash2,
  Utensils,
  Wrench,
  BusFront,
  Ticket,
} from 'lucide-react';
import { Expense, ExpenseCategory } from '../types/expense.types';

const categoryLabels: Record<ExpenseCategory, string> = {
  transport: '交通',
  hotel: '住宿',
  food: '餐饮',
  ticket: '门票',
  parking: '停车',
  shopping: '购物',
  maintenance: '维修',
  activity: '活动',
  other: '其他',
};

const categoryIcons: Record<ExpenseCategory, typeof BusFront> = {
  transport: BusFront,
  hotel: BedDouble,
  food: Utensils,
  ticket: Ticket,
  parking: CircleParking,
  shopping: ShoppingBag,
  maintenance: Wrench,
  activity: Sparkles,
  other: MoreHorizontal,
};

type Props = {
  expenses: Expense[];
  canEdit: boolean;
  onDelete: (expenseId: string) => void;
  onEdit: (expense: Expense) => void;
  onCreate?: () => void;
};

function dayLabel(date: string) {
  const value = new Date(date);
  const today = new Date();
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
  const target = new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const diff = Math.round((start - target) / 86_400_000);
  if (diff === 0) return '今天';
  if (diff === 1) return '昨天';
  return value.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' });
}

function groupExpenses(expenses: Expense[]) {
  const groups = new Map<string, Expense[]>();
  expenses.forEach((expense) => {
    const date = new Date(expense.spentAt);
    const key = `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
    groups.set(key, [...(groups.get(key) ?? []), expense]);
  });
  return Array.from(groups.entries()).map(([key, items]) => ({
    key,
    label: dayLabel(items[0].spentAt),
    date: new Date(items[0].spentAt),
    items,
  }));
}

export function ExpenseList({ expenses, canEdit, onDelete, onEdit, onCreate }: Props) {
  const groups = groupExpenses(expenses);

  return (
    <section className="expense-ledger-section">
      <div className="expense-ledger-heading">
        <div>
          <p className="expense-eyebrow">TRANSACTION LEDGER</p>
          <h2>费用明细</h2>
        </div>
        <span className="expense-count"><ReceiptText size={15} />{expenses.length} 笔记录</span>
      </div>
      {groups.length === 0 ? (
        <div className="expense-empty-state">
          <div className="expense-empty-mark"><ReceiptText size={26} /></div>
          <h3>还没有费用记录</h3>
          <p>把旅途中的每一笔花费记下来，预算会随之变得清晰。</p>
          {onCreate ? <button className="expense-primary-button" onClick={onCreate} type="button"><Plus size={17} />记一笔</button> : null}
        </div>
      ) : (
        <div className="expense-ledger-groups">
          {groups.map((group) => (
            <div className="expense-ledger-group" key={group.key}>
              <div className="expense-day-heading"><strong>{group.label}</strong><span>{group.date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })}</span></div>
              <div className="expense-ledger-list">
                {group.items.map((expense) => {
                  const Icon = categoryIcons[expense.category] ?? MoreHorizontal;
                  return (
                    <article className="expense-ledger-card" key={expense.id}>
                      <div className={`expense-category-icon expense-category-${expense.category}`}><Icon size={19} /></div>
                      <div className="expense-ledger-main">
                        <div className="expense-ledger-title-row"><strong>{expense.title}</strong><span className="expense-status-tag">已记录</span></div>
                        <p className="expense-ledger-meta"><span>{categoryLabels[expense.category]}</span><i />{expense.payer.displayName} 支付<i />{new Date(expense.spentAt).toLocaleDateString('zh-CN')}</p>
                        <p className="expense-ledger-shares">{expense.shares.length ? `已分摊给 ${expense.shares.length} 人 · 人均 ${expense.shares[0]?.shareAmount ?? expense.amount}` : '未设置分摊'}</p>
                      </div>
                      <div className="expense-ledger-amount"><strong>{expense.amount}</strong><span>{expense.currency}</span></div>
                      {canEdit ? <div className="expense-ledger-actions"><button aria-label={`编辑${expense.title}`} onClick={() => onEdit(expense)} type="button"><Edit3 size={16} /></button><button aria-label={`删除${expense.title}`} className="is-danger" onClick={() => onDelete(expense.id)} type="button"><Trash2 size={16} /></button></div> : null}
                    </article>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
