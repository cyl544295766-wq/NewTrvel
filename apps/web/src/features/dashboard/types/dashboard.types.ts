export type DashboardTrip = {
  id: string;
  title: string;
  destination: string | null;
  startDate: string | null;
  endDate: string | null;
  status: string;
  isFavorite: boolean;
  updatedAt: string;
};

export type DashboardExpense = {
  id: string;
  tripId: string;
  tripTitle: string;
  title: string;
  amount: string;
  currency: string;
  category: string;
  spentAt: string;
  createdAt: string;
  payerDisplayName: string;
};

export type DashboardPhoto = {
  id: string;
  tripId: string;
  tripTitle: string;
  thumbnailUrl: string;
  alt: string;
};

export type DashboardStats = {
  tripCount: number;
  totalExpenseAmount: string;
  totalDays: number;
};

export type DashboardData = {
  stats: DashboardStats;
  recentTrips: DashboardTrip[];
  upcomingTrips: DashboardTrip[];
  recentExpenses: DashboardExpense[];
  recentPhotos: DashboardPhoto[];
};
