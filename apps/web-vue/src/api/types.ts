export interface PublicUser {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: string;
  createdAt: string;
}

export interface Trip {
  id: string;
  title: string;
  description: string | null;
  destination: string | null;
  startDate: string | null;
  endDate: string | null;
  status: string;
  coverImageUrl: string | null;
  budget: string | null;
  ownerId: string;
  currentUserRole: string;
  isFavorite: boolean;
  archivedAt: string | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TripDay {
  id: string;
  tripId: string;
  date: string;
  dayIndex: number;
  title: string | null;
  summary: string | null;
  createdAt: string;
  updatedAt: string;
  places: TripPlace[];
}

export interface TripPlace {
  id: string;
  tripId: string;
  tripDayId: string | null;
  name: string;
  type: string;
  address: string | null;
  latitude: string | null;
  longitude: string | null;
  startTime: string | null;
  endTime: string | null;
  notes: string | null;
  sortOrder: number;
  isCompleted: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TripMember {
  id: string;
  userId: string;
  username: string;
  displayName: string;
  role: string;
}

export interface TripExpense {
  id: string;
  title: string;
  amount: string;
  currency: string;
  category: string;
  spentAt: string;
  notes: string | null;
  payer: { id: string; displayName: string };
  shares: Array<{ userId: string; displayName: string; shareAmount: string }>;
}

export interface ExpenseSummary {
  budget: string | null;
  totalExpenseAmount: string;
  remainingBudget: string;
  categoryBreakdown: Array<{ category: string; label: string; amount: string }>;
  payerBreakdown: Array<{ displayName: string; amount: string }>;
  memberBalances: Array<{ displayName: string; paid: string; owed: string; balance: string }>;
}

export interface PackingList {
  id: string;
  tripId: string;
  name: string;
  category: string;
  packedCount: number;
  itemCount: number;
  createdAt: string;
  updatedAt: string;
  items: PackingItem[];
}

export interface PackingItem {
  id: string;
  packingListId: string;
  name: string;
  quantity: number;
  isPacked: boolean;
  notes: string | null;
  orderIndex: number;
}

export interface Photo {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  url: string;
  caption: string | null;
  shotAt: string | null;
  isCover: boolean;
  createdAt: string;
  updatedAt: string;
  tripDay?: { id: string; dayIndex: number; date: string } | null;
  tripPlace?: { id: string; name: string } | null;
}

export interface TravelDocument {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  type: string;
  title: string;
  url: string;
  notes: string | null;
  expiredAt: string | null;
  isReminder: boolean;
  createdAt: string;
  updatedAt: string;
  tripDay?: { id: string; dayIndex: number; date: string } | null;
  tripPlace?: { id: string; name: string } | null;
}

export interface TripJournal {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  title: string;
  content: string;
  isDraft: boolean;
  mood: string | null;
  createdAt: string;
  updatedAt: string;
  tripDay?: { id: string; dayIndex: number; date: string } | null;
  tripPlace?: { id: string; name: string } | null;
  photos: Array<{ id: string; url: string; caption: string | null; orderIndex: number }>;
}

export interface AppNotification {
  id: string;
  userId: string;
  type: string;
  title: string;
  content: string;
  link: string | null;
  metadata: Record<string, unknown> | null;
  isRead: boolean;
  priority: string;
  readAt: string | null;
  createdAt: string;
  expiresAt: string | null;
}

export interface WeatherRow {
  id: string;
  tripId: string;
  tripDayId: string | null;
  date: string;
  destination: string;
  condition: string;
  tempHigh: number;
  tempLow: number;
  rainChance: number | null;
  iconCode: string | null;
  fetchedAt: string;
}

export interface DashboardData {
  user: { id: string; username: string };
  stats: {
    tripCount: number;
    totalExpenseAmount: string;
    totalDays: number;
    pendingPackingItemCount: number;
    unreadNotificationCount: number;
  };
  recentTrips: Trip[];
  upcomingTrips: Trip[];
  recentExpenses: Array<{
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
  }>;
  recentPhotos: Array<{ id: string; tripId: string; tripTitle: string; thumbnailUrl: string; alt: string }>;
  upcomingDocuments: Array<{
    id: string;
    tripId: string;
    tripTitle: string;
    type: string;
    title: string;
    expiredAt: string;
  }>;
  recentJournals: Array<{
    id: string;
    tripId: string;
    tripTitle: string;
    title: string;
    summary: string;
    createdAt: string;
  }>;
}

export interface PlaceSuggestion {
  id: string;
  name: string;
  address: string | null;
  district: string | null;
  city: string | null;
  province: string | null;
  type: string;
  sourceType: string | null;
  latitude: string | null;
  longitude: string | null;
}

export interface RouteDay {
  dayId: string;
  dayIndex: number;
  date: string;
  places: Array<{
    id: string;
    name: string;
    type: string;
    latitude: string;
    longitude: string;
    sortOrder: number;
  }>;
}
