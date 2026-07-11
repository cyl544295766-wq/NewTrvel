import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ExpenseCategoryChart } from './ExpenseCategoryChart';
import { MonthlyTripsChart } from './MonthlyTripsChart';
import { YearlyComparisonChart } from './YearlyComparisonChart';

describe('travel statistics charts', () => {
  it('renders expense, monthly and yearly chart containers', () => {
    render(
      <>
        <ExpenseCategoryChart
          data={{
            primaryCurrency: 'CNY',
            totalExpenseAmount: '100.00',
            categories: [
              { category: 'food', label: '餐饮', amount: '100.00', percentage: 100 },
            ],
          }}
        />
        <MonthlyTripsChart
          data={{
            year: 2026,
            primaryCurrency: 'CNY',
            months: [
              { month: 1, label: '一月', tripCount: 1, totalDays: 3, totalExpenseAmount: '100.00' },
            ],
          }}
        />
        <YearlyComparisonChart
          data={[{ year: 2026, tripCount: 1, totalDays: 3, totalExpenseAmount: '100.00' }]}
        />
      </>,
    );

    expect(screen.getByRole('img', { name: '费用分类占比图表' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '月度旅行次数图表' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '年度旅行次数对比图表' })).toBeInTheDocument();
  });
});
