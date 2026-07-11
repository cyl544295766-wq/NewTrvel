import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ExpenseCategoryChart } from '../components/ExpenseCategoryChart';
import { MonthlyTripsChart } from '../components/MonthlyTripsChart';
import { StatGrid } from '../components/StatGrid';
import { YearlyComparisonChart } from '../components/YearlyComparisonChart';
import { YearlySelector } from '../components/YearlySelector';
import {
  useExpenseCategoryStats,
  useMonthlyStats,
  useStatsOverview,
  useYearlyStats,
} from '../hooks/useTravelStats';

export function StatsOverviewPage() {
  const [year, setYear] = useState(new Date().getFullYear());
  const overview = useStatsOverview();
  const yearly = useYearlyStats(year);
  const monthly = useMonthlyStats(year);
  const expenses = useExpenseCategoryStats();

  useEffect(() => {
    const years = overview.data?.availableYears;
    if (years?.length && !years.includes(year)) setYear(years[0]);
  }, [overview.data?.availableYears, year]);

  if (overview.isLoading) return <main className="loading-shell">统计数据加载中...</main>;
  if (overview.isError || !overview.data) {
    return <main className="loading-shell">统计数据加载失败，请稍后重试</main>;
  }

  const summary = yearly.data ?? overview.data;

  return (
    <main className="app-page travel-stats-page">
      <Link className="text-link" to="/">
        返回首页
      </Link>
      <header className="top-bar travel-stats-heading">
        <div>
          <p className="eyebrow">年度回顾</p>
          <h1>旅行统计</h1>
          <p>从目的地、时间和花费回顾你的旅行足迹。</p>
        </div>
        <YearlySelector
          onChange={setYear}
          value={year}
          years={overview.data.availableYears}
        />
      </header>

      <StatGrid stats={summary} />

      <section className="travel-stats-charts">
        {monthly.data ? <MonthlyTripsChart data={monthly.data} /> : null}
        {expenses.data ? <ExpenseCategoryChart data={expenses.data} /> : null}
        <YearlyComparisonChart data={overview.data.yearlyComparison} />
      </section>
    </main>
  );
}
