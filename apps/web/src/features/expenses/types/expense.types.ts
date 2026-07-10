export type ExpenseCategory =
  | 'transport'
  | 'hotel'
  | 'food'
  | 'ticket'
  | 'parking'
  | 'shopping'
  | 'maintenance'
  | 'activity'
  | 'other';

export type TripMember = { userId: string; username: string; displayName: string; role: string };
export type ExpenseShare = { userId: string; displayName: string; shareAmount: string };
export type ExpenseShareInput = { userId: string; shareAmount: string };

export type Expense = {
  id: string;
  title: string;
  amount: string;
  currency: string;
  category: ExpenseCategory;
  spentAt: string;
  notes: string | null;
  payer: { id: string; displayName: string };
  shares: ExpenseShare[];
};

export type ExpenseSummaryRow = { userId: string; displayName: string; amount: string };
export type LegacyExpenseSummary = {
  currency: string;
  totalAmount: string;
  paidByUser: ExpenseSummaryRow[];
  shareByUser: ExpenseSummaryRow[];
  balanceByUser: ExpenseSummaryRow[];
};

export type ExpenseSummary = {
  budget: string | null;
  totalExpenseAmount: string;
  remainingBudget: string;
  categoryBreakdown: { category: string; label: string; amount: string }[];
  payerBreakdown: { displayName: string; amount: string }[];
  memberBalances: { displayName: string; paid: string; owed: string; balance: string }[];
};

export type ExpenseInput = {
  title: string;
  amount: string;
  currency: string;
  category: ExpenseCategory;
  payerUserId: string;
  shares: ExpenseShareInput[];
  spentAt: string;
  notes?: string;
};
