export type StatsSummary = {
  primaryCurrency: string;
  tripCount: number;
  totalDays: number;
  totalExpenseAmount: string;
  destinationCount: number;
  averageTripDays: number;
  averageExpensePerTrip: string;
  mostVisitedDestination: { label: string | null; count: number };
  busiestMonth: { month: number; label: string | null; count: number };
};

export type StatsOverview = StatsSummary & {
  availableYears: number[];
  yearlyComparison: YearlyComparisonItem[];
};

export type YearlyStats = StatsSummary & { year: number };

export type MonthlyStatsItem = {
  month: number;
  label: string;
  tripCount: number;
  totalDays: number;
  totalExpenseAmount: string;
};

export type MonthlyStats = {
  year: number;
  primaryCurrency: string;
  months: MonthlyStatsItem[];
};

export type ExpenseCategoryStats = {
  primaryCurrency: string;
  totalExpenseAmount: string;
  categories: {
    category: string;
    label: string;
    amount: string;
    percentage: number;
  }[];
};

export type YearlyComparisonItem = {
  year: number;
  tripCount: number;
  totalDays: number;
  totalExpenseAmount: string;
};
