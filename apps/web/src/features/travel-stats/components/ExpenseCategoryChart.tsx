import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ExpenseCategoryStats } from '../types/travel-stats.types';

type ExpenseCategoryChartProps = { data: ExpenseCategoryStats };

export function ExpenseCategoryChart({ data }: ExpenseCategoryChartProps) {
  const chartData = data.categories.filter((item) => Number(item.amount) > 0);

  return (
    <section className="stats-chart-panel">
      <header>
        <p className="eyebrow">费用结构</p>
        <h2>分类占比</h2>
      </header>
      {chartData.length === 0 ? (
        <p className="empty-state compact-empty">暂无费用数据</p>
      ) : (
        <div className="stats-chart" role="img" aria-label="费用分类占比图表">
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={chartData} layout="vertical" margin={{ left: 8, right: 24 }}>
              <CartesianGrid horizontal={false} stroke="#d8ded7" />
              <XAxis type="number" tickLine={false} axisLine={false} />
              <YAxis dataKey="label" type="category" width={54} tickLine={false} axisLine={false} />
              <Tooltip formatter={(value) => [`${data.primaryCurrency} ${value}`, '金额']} />
              <Bar dataKey="amount" fill="#356046" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
