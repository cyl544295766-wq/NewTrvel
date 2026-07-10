import { ExpenseSummary as Summary } from '../types/expense.types';

type Props = { summary: Summary };

export function ExpenseSummary({ summary }: Props) {
  return (
    <section className="summary-grid">
      <div className="summary-card">
        <span>总支出</span>
        <strong>
          {summary.totalAmount} {summary.currency}
        </strong>
      </div>
      <SummaryList title="已付款" rows={summary.paidByUser} />
      <SummaryList title="应分摊" rows={summary.shareByUser} />
      <SummaryList title="余额" rows={summary.balanceByUser} />
    </section>
  );
}

function SummaryList({
  title,
  rows,
}: {
  title: string;
  rows: { displayName: string; amount: string }[];
}) {
  return (
    <div className="summary-card">
      <span>{title}</span>
      {rows.map((row) => (
        <p key={`${title}-${row.displayName}`}>
          {row.displayName}: {row.amount}
        </p>
      ))}
    </div>
  );
}
