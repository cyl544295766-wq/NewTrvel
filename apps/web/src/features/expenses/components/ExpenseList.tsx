import { ReceiptText } from 'lucide-react';
import { Expense } from '../types/expense.types';

const categoryLabels: Record<string, string> = {
  transport: '油费/交通',
  hotel: '住宿',
  food: '餐饮',
  ticket: '门票',
  parking: '停车',
  shopping: '购物',
  maintenance: '维修',
  activity: '活动',
  other: '其他',
};

type Props = {
  expenses: Expense[];
  canEdit: boolean;
  onDelete: (expenseId: string) => void;
  onEdit: (expense: Expense) => void;
};

export function ExpenseList({ expenses, canEdit, onDelete, onEdit }: Props) {
  return (
    <section className="wallet-expense-section">
      <div className="wallet-expense-heading">
        <div>
          <p className="eyebrow">明细</p>
          <h2>消费记录</h2>
        </div>
        <span>
          <ReceiptText aria-hidden="true" size={16} />
          {expenses.length} 笔
        </span>
      </div>
      {expenses.length === 0 ? (
        <p className="empty-state compact-empty">暂无消费记录，去记一笔吧</p>
      ) : (
        <div className="expense-list">
          {expenses.map((expense) => (
            <article className="expense-item" key={expense.id}>
              <div>
                <strong>{expense.title}</strong>
                <p>
                  {categoryLabels[expense.category]} · {expense.payer.displayName} 付款 ·{' '}
                  {new Date(expense.spentAt).toLocaleDateString('zh-CN')}
                </p>
                <p>
                  {expense.shares
                    .map((share) => `${share.displayName} ${share.shareAmount}`)
                    .join('，')}
                </p>
              </div>
              <div className="expense-amount">
                <strong>
                  {expense.amount} {expense.currency}
                </strong>
                {canEdit ? (
                  <div className="expense-actions">
                    <button
                      className="secondary-button"
                      onClick={() => onEdit(expense)}
                      type="button"
                    >
                      编辑
                    </button>
                    <button
                      className="secondary-button danger-button"
                      onClick={() => onDelete(expense.id)}
                      type="button"
                    >
                      删除
                    </button>
                  </div>
                ) : null}
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
