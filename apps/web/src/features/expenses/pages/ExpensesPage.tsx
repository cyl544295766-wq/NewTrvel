import { FormEvent, useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useCurrentUser } from '../../auth';
import { useTrip, useUpdateTrip } from '../../trips/hooks/useTrips';
import { ExpenseForm } from '../components/ExpenseForm';
import { ExpenseList } from '../components/ExpenseList';
import { ExpenseSummary } from '../components/ExpenseSummary';
import {
  useCreateExpense,
  useDeleteExpense,
  useExpenses,
  useExpenseSummary,
  useTripMembers,
  useUpdateExpense,
} from '../hooks/useExpenses';
import { Expense, ExpenseInput } from '../types/expense.types';

export function ExpensesPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const currentUser = useCurrentUser();
  const trip = useTrip(safeTripId, Boolean(tripId));
  const updateTrip = useUpdateTrip(safeTripId);
  const expenses = useExpenses(safeTripId);
  const summary = useExpenseSummary(safeTripId);
  const members = useTripMembers(safeTripId);
  const createExpense = useCreateExpense(safeTripId);
  const updateExpense = useUpdateExpense(safeTripId);
  const deleteExpense = useDeleteExpense(safeTripId);
  const [budget, setBudget] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingExpense, setEditingExpense] = useState<Expense | null>(null);

  useEffect(() => {
    setBudget(trip.data?.trip.budget ?? '');
  }, [trip.data?.trip.budget]);

  if (!tripId) return <Navigate replace to="/" />;
  const currentTrip = trip.data?.trip;
  const walletSummary = summary.data;
  const canEdit = currentTrip
    ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole)
    : false;
  const remainingBudget = Number(walletSummary?.remainingBudget ?? 0);

  async function handleBudgetSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await updateTrip.mutateAsync({ budget: budget.trim() === '' ? null : budget });
    summary.refetch();
  }

  async function handleSubmit(input: ExpenseInput) {
    if (editingExpense) {
      await updateExpense.mutateAsync({ expenseId: editingExpense.id, input });
    } else {
      await createExpense.mutateAsync(input);
    }
    setEditingExpense(null);
    setShowForm(false);
  }

  function handleEdit(expense: Expense) {
    setEditingExpense(expense);
    setShowForm(true);
  }

  function handleCancel() {
    setEditingExpense(null);
    setShowForm(false);
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">费用</p>
            <h1>旅行钱包</h1>
          </div>
          {canEdit ? (
            <button onClick={() => setShowForm(true)} type="button">
              记一笔
            </button>
          ) : null}
        </div>

        <section className="summary-grid">
          <div className="summary-card">
            <span>预算</span>
            <strong>{walletSummary?.budget ? walletSummary.budget : '未设置预算'}</strong>
            {canEdit ? (
              <form className="inline-budget-form" onSubmit={handleBudgetSubmit}>
                <input
                  min="0"
                  placeholder="设置预算"
                  step="0.01"
                  type="number"
                  value={budget}
                  onChange={(event) => setBudget(event.target.value)}
                />
                <button disabled={updateTrip.isPending} type="submit">
                  保存
                </button>
              </form>
            ) : null}
          </div>
          <div className="summary-card">
            <span>已消费</span>
            <strong>{walletSummary?.totalExpenseAmount ?? '0.00'}</strong>
          </div>
          <div className={remainingBudget < 0 ? 'summary-card danger-card' : 'summary-card'}>
            <span>剩余预算</span>
            <strong>{walletSummary?.budget ? walletSummary.remainingBudget : '未设置预算'}</strong>
          </div>
        </section>

        {walletSummary ? <ExpenseSummary summary={walletSummary} /> : null}

        {showForm && canEdit ? (
          <ExpenseForm
            currentUserId={currentUser.data?.user.id}
            initialExpense={editingExpense}
            isSubmitting={createExpense.isPending || updateExpense.isPending}
            members={members.data?.members ?? []}
            onCancel={handleCancel}
            onSubmit={handleSubmit}
          />
        ) : null}

        <ExpenseList
          canEdit={canEdit}
          expenses={expenses.data?.expenses ?? []}
          onDelete={(expenseId) => deleteExpense.mutate(expenseId)}
          onEdit={handleEdit}
        />
      </section>
    </main>
  );
}
