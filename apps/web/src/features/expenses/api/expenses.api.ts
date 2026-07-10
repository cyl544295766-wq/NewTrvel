import { request } from '../../../services/http';
import {
  Expense,
  ExpenseInput,
  ExpenseSummary,
  LegacyExpenseSummary,
  TripMember,
} from '../types/expense.types';

export function getExpenses(tripId: string) {
  return request<{ expenses: Expense[]; summary: LegacyExpenseSummary }>(
    `/trips/${tripId}/expenses`,
  );
}

export function getExpenseSummary(tripId: string): Promise<ExpenseSummary> {
  return request<ExpenseSummary>(`/trips/${tripId}/expenses/summary`);
}

export function createExpense(tripId: string, input: ExpenseInput) {
  return request<{ expense: Expense }>(`/trips/${tripId}/expenses`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function deleteExpense(tripId: string, expenseId: string) {
  return request<{ success: true }>(`/trips/${tripId}/expenses/${expenseId}`, { method: 'DELETE' });
}

export function updateExpense(tripId: string, expenseId: string, input: Partial<ExpenseInput>) {
  return request<{ expense: Expense }>(`/trips/${tripId}/expenses/${expenseId}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function getTripMembers(tripId: string) {
  return request<{ members: TripMember[] }>(`/trips/${tripId}/members`);
}
