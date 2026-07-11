import { request } from '../../../services/http';
import {
  ExpenseCategoryStats,
  MonthlyStats,
  StatsOverview,
  YearlyStats,
} from '../types/travel-stats.types';

export function getStatsOverview(): Promise<StatsOverview> {
  return request<StatsOverview>('/stats/overview');
}

export function getYearlyStats(year: number): Promise<YearlyStats> {
  return request<YearlyStats>(`/stats/yearly?year=${year}`);
}

export function getMonthlyStats(year: number): Promise<MonthlyStats> {
  return request<MonthlyStats>(`/stats/monthly?year=${year}`);
}

export function getExpenseCategoryStats(): Promise<ExpenseCategoryStats> {
  return request<ExpenseCategoryStats>('/stats/expense-categories');
}
