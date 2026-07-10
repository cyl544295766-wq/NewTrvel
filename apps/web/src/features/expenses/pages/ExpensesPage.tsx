import { Link, Navigate, useParams } from 'react-router-dom';
import { useTrip } from '../../trips/hooks/useTrips';
import { ExpenseForm } from '../components/ExpenseForm';
import { ExpenseList } from '../components/ExpenseList';
import { ExpenseSummary } from '../components/ExpenseSummary';
import {
  useCreateExpense,
  useDeleteExpense,
  useExpenses,
  useTripMembers,
} from '../hooks/useExpenses';
import { ExpenseInput } from '../types/expense.types';

export function ExpensesPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const trip = useTrip(safeTripId, Boolean(tripId));
  const expenses = useExpenses(safeTripId);
  const members = useTripMembers(safeTripId);
  const createExpense = useCreateExpense(safeTripId);
  const deleteExpense = useDeleteExpense(safeTripId);

  if (!tripId) return <Navigate replace to="/" />;
  const currentTrip = trip.data?.trip;
  const canEdit = currentTrip
    ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole)
    : false;

  async function handleCreate(input: ExpenseInput) {
    await createExpense.mutateAsync(input);
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
            <h1>{currentTrip?.title ?? '旅行费用'}</h1>
          </div>
        </div>
        {expenses.data ? <ExpenseSummary summary={expenses.data.summary} /> : null}
        {canEdit ? (
          <ExpenseForm
            isSubmitting={createExpense.isPending}
            members={members.data?.members ?? []}
            onSubmit={handleCreate}
          />
        ) : null}
        <ExpenseList
          canEdit={canEdit}
          expenses={expenses.data?.expenses ?? []}
          onDelete={(expenseId) => deleteExpense.mutate(expenseId)}
        />
      </section>
    </main>
  );
}
