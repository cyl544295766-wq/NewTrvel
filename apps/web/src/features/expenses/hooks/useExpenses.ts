import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createExpense, deleteExpense, getExpenses, getTripMembers } from '../api/expenses.api';
import { ExpenseInput } from '../types/expense.types';

const expensesKey = (tripId: string) => ['expenses', tripId];
const membersKey = (tripId: string) => ['trip-members', tripId];

export function useExpenses(tripId: string) {
  return useQuery({
    queryKey: expensesKey(tripId),
    queryFn: () => getExpenses(tripId),
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
    onSuccess: () => queryClient.invalidateQueries({ queryKey: expensesKey(tripId) }),
  });
}
export function useDeleteExpense(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expenseId: string) => deleteExpense(tripId, expenseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: expensesKey(tripId) }),
  });
}
