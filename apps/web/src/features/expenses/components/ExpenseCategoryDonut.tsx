import { ExpenseSummary } from '../types/expense.types';

type Props = { breakdown: ExpenseSummary['categoryBreakdown'] };

const palette = ['#2d4f3c', '#c27a42', '#657b93', '#8b6f9d', '#bf6f87', '#8a8a82', '#b85c4c', '#4d8b83', '#9b8a68'];

export function ExpenseCategoryDonut({ breakdown }: Props) {
  const total = breakdown.reduce((sum, item) => sum + Number(item.amount), 0);
  let cursor = 0;
  const stops = breakdown.map((item, index) => {
    const start = total > 0 ? (cursor / total) * 100 : 0;
    cursor += Number(item.amount);
    const end = total > 0 ? (cursor / total) * 100 : 100;
    return `${palette[index % palette.length]} ${start}% ${end}%`;
  });

  return (
    <div className="expense-donut-wrap">
      <div
        aria-label="费用分类占比"
        className="expense-donut"
        style={{ background: stops.length ? `conic-gradient(${stops.join(', ')})` : '#e8e6df' }}
        role="img"
      >
        <div className="expense-donut-hole">
          <strong>{total.toFixed(2)}</strong>
          <span>总花费</span>
        </div>
      </div>
      <div className="expense-donut-legend">
        {breakdown.length === 0 ? <span className="expense-muted">还没有分类支出</span> : null}
        {breakdown.map((item, index) => (
          <div className="expense-legend-row" key={item.category}>
            <span className="expense-legend-dot" style={{ background: palette[index % palette.length] }} />
            <span>{item.label}</span>
            <strong>{item.amount}</strong>
          </div>
        ))}
      </div>
    </div>
  );
}
