import { Link } from 'react-router-dom';
import { DashboardExpense } from '../types/dashboard.types';

type RecentExpenseListProps = {
  expenses: DashboardExpense[];
};

export function RecentExpenseList({ expenses }: RecentExpenseListProps) {
  return (
    <section className="content-panel dashboard-card">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">费用</p>
          <h2>最近消费</h2>
        </div>
      </div>
      {expenses.length === 0 ? (
        <p className="empty-state">暂无消费记录</p>
      ) : (
        <div className="dashboard-list">
          {expenses.map((expense) => (
            <Link
              className="dashboard-list-row"
              key={expense.id}
              to={`/trips/${expense.tripId}/expenses`}
            >
              <div>
                <strong>{expense.title}</strong>
                <span>
                  {expense.tripTitle} · {expense.payerDisplayName}
                </span>
              </div>
              <b>{formatMoney(expense.currency, expense.amount)}</b>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function formatMoney(currency: string, amount: string) {
  const currencyNames: Record<string, string> = {
    CNY: '人民币',
    USD: '美元',
    EUR: '欧元',
    JPY: '日元',
    HKD: '港币',
  };

  return `${currencyNames[currency] ?? '其他币种'} ${amount}`;
}
