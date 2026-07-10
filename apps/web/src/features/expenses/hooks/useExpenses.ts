import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createExpense,
  deleteExpense,
  getExpenseSummary,
  getExpenses,
  getTripMembers,
  updateExpense,
} from '../api/expenses.api';
import { ExpenseInput } from '../types/expense.types';

const expensesKey = (tripId: string) => ['expenses', tripId];
const expenseSummaryKey = (tripId: string) => ['expense-summary', tripId];
const membersKey = (tripId: string) => ['trip-members', tripId];

export function useExpenses(tripId: string) {
  return useQuery({
    queryKey: expensesKey(tripId),
    queryFn: () => getExpenses(tripId),
    enabled: Boolean(tripId),
  });
}

export function useExpenseSummary(tripId: string) {
  return useQuery({
    queryKey: expenseSummaryKey(tripId),
    queryFn: () => getExpenseSummary(tripId),
    enabled: Boolean(tripId),
  });
}

export function useTripMembers(tripId: string) {
  return useQuery({
    queryKey: membersKey(tripId),
    queryFn: () => getTripMembers(tripId),
    enabled: Boolean(tripId),
  });
}

export function useCreateExpense(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ExpenseInput) => createExpense(tripId, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: expensesKey(tripId) });
      queryClient.invalidateQueries({ queryKey: expenseSummaryKey(tripId) });
    },
  });
}

export function useUpdateExpense(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ expenseId, input }: { expenseId: string; input: Partial<ExpenseInput> }) =>
      updateExpense(tripId, expenseId, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: expensesKey(tripId) });
      queryClient.invalidateQueries({ queryKey: expenseSummaryKey(tripId) });
    },
  });
}

export function useDeleteExpense(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expenseId: string) => deleteExpense(tripId, expenseId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: expensesKey(tripId) });
      queryClient.invalidateQueries({ queryKey: expenseSummaryKey(tripId) });
    },
  });
}
