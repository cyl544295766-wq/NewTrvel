import { Expense } from '../types/expense.types';

type Props = { expenses: Expense[]; canEdit: boolean; onDelete: (expenseId: string) => void };

export function ExpenseList({ expenses, canEdit, onDelete }: Props) {
  if (expenses.length === 0) return <p className="empty-state">暂无费用</p>;
  return (
    <div className="expense-list">
      {expenses.map((expense) => (
        <article className="expense-item" key={expense.id}>
          <div>
            <strong>{expense.title}</strong>
            <p>
              {expense.payer.displayName} 付款 · {new Date(expense.spentAt).toLocaleString('zh-CN')}
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
              <button
                className="secondary-button"
                onClick={() => onDelete(expense.id)}
                type="button"
              >
                删除
              </button>
            ) : null}
          </div>
        </article>
      ))}
    </div>
  );
}
