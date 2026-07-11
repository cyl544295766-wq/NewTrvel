import { ArrowLeft, ArrowUpDown, CalendarDays, Download, Filter, PiggyBank, Plus, Search, WalletCards } from 'lucide-react';
import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useCurrentUser } from '../../auth';
import { useTrip, useUpdateTrip } from '../../trips/hooks/useTrips';
import { ExpenseForm } from '../components/ExpenseForm';
import { ExpenseCategoryDonut } from '../components/ExpenseCategoryDonut';
import { ExpenseList } from '../components/ExpenseList';
import {
  useCreateExpense,
  useDeleteExpense,
  useExpenses,
  useExpenseSummary,
  useTripMembers,
  useUpdateExpense,
} from '../hooks/useExpenses';
import { Expense, ExpenseCategory, ExpenseInput } from '../types/expense.types';

const categoryLabels: Record<ExpenseCategory, string> = {
  transport: '交通', hotel: '住宿', food: '餐饮', ticket: '门票', parking: '停车', shopping: '购物', maintenance: '维修', activity: '活动', other: '其他',
};
const categoryOptions: Array<ExpenseCategory | 'all'> = ['all', 'transport', 'hotel', 'food', 'ticket', 'parking', 'shopping', 'maintenance', 'activity', 'other'];

type SortMode = 'latest' | 'high' | 'low';

function formatMoney(value: string | number, currency = 'CNY') {
  const amount = Number(value);
  return `${currency === 'CNY' ? '¥' : currency} ${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function budgetTone(budget: number, total: number) {
  if (!budget || total / budget < 0.5) return 'safe';
  if (total / budget < 0.8) return 'watch';
  if (total / budget <= 1) return 'warning';
  return 'over';
}

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
  const [category, setCategory] = useState<ExpenseCategory | 'all'>('all');
  const [sort, setSort] = useState<SortMode>('latest');
  const [search, setSearch] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const formSectionRef = useRef<HTMLElement>(null);

  useEffect(() => setBudget(trip.data?.trip.budget ?? ''), [trip.data?.trip.budget]);
  useEffect(() => {
    if (!showForm) return;
    const frame = window.requestAnimationFrame(() => {
      formSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      formSectionRef.current?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [showForm, editingExpense]);

  const currentTrip = trip.data?.trip;
  const walletSummary = summary.data;
  const canEdit = currentTrip ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole) : false;
  const allExpenses = expenses.data?.expenses ?? [];
  const total = Number(walletSummary?.totalExpenseAmount ?? 0);
  const budgetAmount = Number(walletSummary?.budget ?? 0);
  const remaining = Number(walletSummary?.remainingBudget ?? 0);
  const tone = budgetTone(budgetAmount, total);
  const filteredExpenses = useMemo(() => {
    const query = search.trim().toLowerCase();
    return allExpenses
      .filter((expense) => category === 'all' || expense.category === category)
      .filter((expense) => !query || `${expense.title} ${expense.notes ?? ''} ${expense.payer.displayName}`.toLowerCase().includes(query))
      .filter((expense) => !dateFrom || expense.spentAt.slice(0, 10) >= dateFrom)
      .filter((expense) => !dateTo || expense.spentAt.slice(0, 10) <= dateTo)
      .sort((a, b) => sort === 'latest' ? +new Date(b.spentAt) - +new Date(a.spentAt) : sort === 'high' ? Number(b.amount) - Number(a.amount) : Number(a.amount) - Number(b.amount));
  }, [allExpenses, category, dateFrom, dateTo, search, sort]);

  if (!tripId) return <Navigate replace to="/" />;

  async function handleBudgetSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await updateTrip.mutateAsync({ budget: budget.trim() === '' ? null : budget });
    await summary.refetch();
  }

  async function handleSubmit(input: ExpenseInput) {
    if (editingExpense) await updateExpense.mutateAsync({ expenseId: editingExpense.id, input });
    else await createExpense.mutateAsync(input);
    setEditingExpense(null);
    setShowForm(false);
  }

  function handleCreate() { setEditingExpense(null); setShowForm(true); }
  function handleCancel() { setEditingExpense(null); setShowForm(false); }
  function exportExpenses() {
    const rows = [['日期', '标题', '分类', '支付人', '金额', '币种', '备注'], ...filteredExpenses.map((expense) => [new Date(expense.spentAt).toLocaleDateString('zh-CN'), expense.title, categoryLabels[expense.category], expense.payer.displayName, expense.amount, expense.currency, expense.notes ?? ''])];
    const csv = rows.map((row) => row.map((value) => `"${value.replaceAll('"', '""')}"`).join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${currentTrip?.title ?? '旅行'}-费用.csv`; anchor.click(); URL.revokeObjectURL(url);
  }

  return (
    <main className="expenses-editorial-page">
      <aside className="expenses-sidebar">
        <Link className="expenses-back-link" to={`/trips/${safeTripId}`}><ArrowLeft size={15} />返回旅行详情</Link>
        <div className="expenses-trip-cover" style={currentTrip?.coverImageUrl ? { backgroundImage: `url(${currentTrip.coverImageUrl})` } : undefined}>
          <div><span>TRAVEL LEDGER</span><strong>{currentTrip?.destination || '未设置目的地'}</strong></div>
        </div>
        <div className="expenses-sidebar-heading"><p className="expense-eyebrow">PERSONAL TRIP ACCOUNT</p><h1>费用管理</h1><p>{currentTrip?.title ?? '这段旅程'} · {currentTrip?.destination ?? '目的地待定'}</p></div>
        <section className={`expenses-budget-card is-${tone}`}>
          <div className="expenses-card-label"><span>旅行预算</span><PiggyBank size={17} /></div>
          <strong>{walletSummary?.budget ? formatMoney(walletSummary.budget) : '尚未设置'}</strong>
          <div className="expenses-budget-track"><i style={{ width: `${budgetAmount ? Math.min((total / budgetAmount) * 100, 100) : 0}%` }} /></div>
          <div className="expenses-budget-meta"><span>已用 {formatMoney(total)}</span><span>{budgetAmount ? (total / budgetAmount * 100).toFixed(0) : 0}%</span></div>
          <div className="expenses-budget-balance"><span>剩余预算</span><strong>{walletSummary?.budget ? formatMoney(remaining) : '—'}</strong></div>
          {canEdit ? <form className="expenses-budget-form" onSubmit={handleBudgetSubmit}><input aria-label="设置旅行预算" min="0" placeholder="输入预算金额" step="0.01" type="number" value={budget} onChange={(event) => setBudget(event.target.value)} /><button disabled={updateTrip.isPending} type="submit">保存</button></form> : null}
        </section>
        <section className="expenses-mini-stats"><div><span>总花费</span><strong>{formatMoney(total)}</strong></div><div><span>费用笔数</span><strong>{allExpenses.length}</strong></div><div><span>人均花费</span><strong>{formatMoney(allExpenses.length ? total / Math.max(members.data?.members.length ?? 1, 1) : 0)}</strong></div></section>
        {walletSummary ? <section className="expenses-category-card"><div className="expenses-section-title"><span>支出分类</span><WalletCards size={16} /></div><ExpenseCategoryDonut breakdown={walletSummary.categoryBreakdown} /></section> : null}
        <div className="expenses-sidebar-actions"><button className="expense-primary-button" disabled={!canEdit} onClick={handleCreate} type="button"><Plus size={17} />记一笔</button><button className="expense-secondary-button" onClick={exportExpenses} type="button"><Download size={16} />导出费用</button></div>
      </aside>
      <section className="expenses-main-column">
        <header className="expenses-page-header"><div><p className="expense-eyebrow">THE COST OF THE JOURNEY</p><h2>旅行账本</h2><p>所有花费都值得被看见，也值得被好好安排。</p></div><button className="expense-primary-button" disabled={!canEdit} onClick={handleCreate} type="button"><Plus size={17} />记一笔</button></header>
        {showForm && canEdit ? <section className="expenses-form-shell" ref={formSectionRef}><div className="expenses-form-heading"><div><p className="expense-eyebrow">{editingExpense ? 'EDIT ENTRY' : 'NEW ENTRY'}</p><h3>{editingExpense ? '编辑费用记录' : '记录一笔费用'}</h3></div><button aria-label="关闭费用表单" onClick={handleCancel} type="button">×</button></div><ExpenseForm currentUserId={currentUser.data?.user.id} initialExpense={editingExpense} isSubmitting={createExpense.isPending || updateExpense.isPending} members={members.data?.members ?? []} onCancel={handleCancel} onSubmit={handleSubmit} /></section> : null}
        <div className="expenses-filter-bar"><div className="expenses-search"><Search size={17} /><input aria-label="搜索费用" placeholder="搜索标题、备注或支付人" value={search} onChange={(event) => setSearch(event.target.value)} /></div><div className="expenses-filter-scroll"><Filter size={15} />{categoryOptions.map((item) => <button className={category === item ? 'is-active' : ''} key={item} onClick={() => setCategory(item)} type="button">{item === 'all' ? '全部' : categoryLabels[item]}</button>)}</div><label className="expenses-date-filter"><CalendarDays size={15} /><input aria-label="开始日期" type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} /><span>至</span><input aria-label="结束日期" type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} /></label><label className="expenses-sort"><ArrowUpDown size={15} /><select aria-label="排序方式" value={sort} onChange={(event) => setSort(event.target.value as SortMode)}><option value="latest">最新记录</option><option value="high">金额最高</option><option value="low">金额最低</option></select></label></div>
        <ExpenseList canEdit={canEdit} expenses={filteredExpenses} onCreate={handleCreate} onDelete={(expenseId) => deleteExpense.mutate(expenseId)} onEdit={(expense) => { setEditingExpense(expense); setShowForm(true); }} />
      </section>
    </main>
  );
}
