import { ExpenseSummary as Summary } from '../types/expense.types';

type Props = { summary: Summary };

export function ExpenseSummary({ summary }: Props) {
  const budgetAmount = Number(summary.budget ?? 0);
  const totalAmount = Number(summary.totalExpenseAmount);
  const progress = budgetAmount > 0 ? Math.min((totalAmount / budgetAmount) * 100, 100) : 0;

  return (
    <section className="wallet-summary">
      <div className="summary-card wallet-progress-card">
        <span>预算进度</span>
        <strong>
          {summary.budget ? `${summary.totalExpenseAmount} / ${summary.budget}` : '未设置预算'}
        </strong>
        <div className="budget-progress" aria-label="预算进度">
          <div style={{ width: `${progress}%` }} />
        </div>
      </div>
      <div className="summary-card">
        <span>分类统计</span>
        <div className="summary-list">
          {summary.categoryBreakdown.map((item) => (
            <p key={item.category}>
              <span>{item.label}</span>
              <strong>{item.amount}</strong>
            </p>
          ))}
        </div>
      </div>
      <div className="summary-card">
        <span>付款人汇总</span>
        <div className="summary-list">
          {summary.payerBreakdown.length === 0 ? (
            <p>暂无付款记录</p>
          ) : (
            summary.payerBreakdown.map((item) => (
              <p key={item.displayName}>
                <span>{item.displayName}</span>
                <strong>{item.amount}</strong>
              </p>
            ))
          )}
        </div>
      </div>
      <div className="summary-card">
        <span>成员结算清单</span>
        <div className="summary-list">
          {summary.memberBalances.map((item) => (
            <p key={item.displayName}>
              <span>{item.displayName}</span>
              <strong className={Number(item.balance) < 0 ? 'danger-text' : undefined}>
                {Number(item.balance) >= 0
                  ? `应收 ${item.balance}`
                  : `应付 ${Math.abs(Number(item.balance)).toFixed(2)}`}
              </strong>
            </p>
          ))}
        </div>
      </div>
    </section>
  );
}
