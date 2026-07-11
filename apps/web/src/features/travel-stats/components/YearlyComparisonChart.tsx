import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { YearlyComparisonItem } from '../types/travel-stats.types';

type YearlyComparisonChartProps = { data: YearlyComparisonItem[] };

export function YearlyComparisonChart({ data }: YearlyComparisonChartProps) {
  return (
    <section className="stats-chart-panel">
      <header>
        <p className="eyebrow">历年趋势</p>
        <h2>年度旅行对比</h2>
      </header>
      {data.length === 0 ? (
        <p className="empty-state compact-empty">暂无年度数据</p>
      ) : (
        <div className="stats-chart" role="img" aria-label="年度旅行次数对比图表">
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={data} margin={{ left: -16, right: 16, top: 10 }}>
              <CartesianGrid vertical={false} stroke="#d8ded7" />
              <XAxis dataKey="year" tickLine={false} axisLine={false} />
              <YAxis allowDecimals={false} tickLine={false} axisLine={false} />
              <Tooltip formatter={(value) => [`${value} 次`, '旅行']} />
              <Bar dataKey="tripCount" fill="#587080" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
