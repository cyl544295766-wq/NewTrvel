import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import {
  useExpenseCategoryStats,
  useMonthlyStats,
  useStatsOverview,
  useYearlyStats,
} from '../hooks/useTravelStats';
import { StatsOverviewPage } from './StatsOverviewPage';

vi.mock('../hooks/useTravelStats', () => ({
  useStatsOverview: vi.fn(),
  useYearlyStats: vi.fn(),
  useMonthlyStats: vi.fn(),
  useExpenseCategoryStats: vi.fn(),
}));

vi.mock('../components/MonthlyTripsChart', () => ({
  MonthlyTripsChart: () => <div>月度图表</div>,
}));
vi.mock('../components/ExpenseCategoryChart', () => ({
  ExpenseCategoryChart: () => <div>费用图表</div>,
}));
vi.mock('../components/YearlyComparisonChart', () => ({
  YearlyComparisonChart: () => <div>年度图表</div>,
}));

describe('StatsOverviewPage', () => {
  it('renders statistics and switches the selected year', async () => {
    vi.mocked(useStatsOverview).mockReturnValue({
      data: { ...createSummary(), availableYears: [2026, 2025], yearlyComparison: [] },
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof useStatsOverview>);
    vi.mocked(useYearlyStats).mockImplementation(
      (year) =>
        ({
          data: { ...createSummary(), year },
        }) as ReturnType<typeof useYearlyStats>,
    );
    vi.mocked(useMonthlyStats).mockReturnValue({
      data: { year: 2026, primaryCurrency: 'CNY', months: [] },
    } as unknown as ReturnType<typeof useMonthlyStats>);
    vi.mocked(useExpenseCategoryStats).mockReturnValue({
      data: { primaryCurrency: 'CNY', totalExpenseAmount: '300.00', categories: [] },
    } as unknown as ReturnType<typeof useExpenseCategoryStats>);
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <StatsOverviewPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '旅行统计' })).toBeInTheDocument();
    expect(screen.getByText('3 次')).toBeInTheDocument();
    expect(screen.getByText('月度图表')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('回顾年份'), '2025');

    expect(useYearlyStats).toHaveBeenLastCalledWith(2025);
    expect(useMonthlyStats).toHaveBeenLastCalledWith(2025);
  });
});

function createSummary() {
  return {
    primaryCurrency: 'CNY',
    tripCount: 3,
    totalDays: 8,
    totalExpenseAmount: '300.00',
    destinationCount: 2,
    averageTripDays: 2.7,
    averageExpensePerTrip: '100.00',
    mostVisitedDestination: { label: '上海', count: 2 },
    busiestMonth: { month: 3, label: '三月', count: 2 },
  };
}
