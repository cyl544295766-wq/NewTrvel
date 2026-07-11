import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { MonthlyStats } from '../types/travel-stats.types';

type MonthlyTripsChartProps = { data: MonthlyStats };

export function MonthlyTripsChart({ data }: MonthlyTripsChartProps) {
  return (
    <section className="stats-chart-panel stats-chart-wide">
      <header>
        <p className="eyebrow">{data.year} 年</p>
        <h2>月度旅行次数</h2>
      </header>
      <div className="stats-chart" role="img" aria-label="月度旅行次数图表">
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={data.months} margin={{ left: -16, right: 16, top: 10 }}>
            <CartesianGrid vertical={false} stroke="#d8ded7" />
            <XAxis dataKey="label" tickLine={false} axisLine={false} />
            <YAxis allowDecimals={false} tickLine={false} axisLine={false} />
            <Tooltip formatter={(value) => [`${value} 次`, '旅行']} />
            <Line
              dataKey="tripCount"
              stroke="#356046"
              strokeWidth={3}
              dot={{ fill: '#f4f6f1', stroke: '#356046', strokeWidth: 2, r: 4 }}
              activeDot={{ r: 6 }}
              type="monotone"
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </section>
  );
}
