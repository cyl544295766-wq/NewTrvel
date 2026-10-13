import { http, errorMessage } from './http';
import type {
  PackingList,
  Photo,
  PlaceSuggestion,
  PublicUser,
  RouteDay,
  Trip,
  TripDay,
  TripExpense,
  TripJournal,
  TripMember,
  TripPlace,
  TravelDocument,
  WeatherRow,
  ExpenseSummary,
  DashboardData,
  AppNotification,
} from './types';

function unwrap<T>(promise: Promise<{ data: T }>): Promise<T> {
  return promise.then((response) => response.data);
}

// ---------- auth ----------
export const authApi = {
  login: (username: string, password: string) =>
    unwrap<{ user: PublicUser }>(http.post('/auth/login', { username, password })),
  register: (username: string, password: string) =>
    unwrap<{ user: PublicUser }>(http.post('/auth/register', { username, password })),
  deactivate: (password: string) =>
    unwrap<{ success: boolean }>(http.post('/auth/deactivate', { password })),
  logout: () => unwrap(http.post('/auth/logout')),
  me: () => unwrap<{ user: PublicUser }>(http.get('/auth/me')),
};

// ---------- dashboard ----------
export const dashboardApi = {
  get: () => unwrap<DashboardData>(http.get('/dashboard')),
};

// ---------- trips ----------
export const tripsApi = {
  list: () => unwrap<{ trips: Trip[] }>(http.get('/trips')),
  create: (payload: Record<string, unknown>) =>
    unwrap<{ trip: Trip }>(http.post('/trips', payload)),
  detail: (tripId: string) => unwrap<{ trip: Trip }>(http.get(`/trips/${tripId}`)),
  update: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ trip: Trip }>(http.patch(`/trips/${tripId}`, payload)),
  remove: (tripId: string) => unwrap(http.delete(`/trips/${tripId}`)),
  archive: (tripId: string) => unwrap<{ trip: Trip }>(http.patch(`/trips/${tripId}/archive`)),
  favorite: (tripId: string) => unwrap<{ trip: Trip }>(http.patch(`/trips/${tripId}/favorite`)),
  duplicate: (tripId: string) => unwrap<{ trip: Trip }>(http.post(`/trips/${tripId}/duplicate`)),
  route: (tripId: string) => unwrap<{ route: RouteDay[] }>(http.get(`/trips/${tripId}/route`)),
};

// ---------- members ----------
export const membersApi = {
  list: (tripId: string) => unwrap<{ members: TripMember[] }>(http.get(`/trips/${tripId}/members`)),
  invite: (tripId: string, username: string, role?: string) =>
    unwrap<{ members: TripMember[] }>(http.post(`/trips/${tripId}/members`, { username, role })),
  updateRole: (tripId: string, memberId: string, role: string) =>
    unwrap<{ members: TripMember[] }>(
      http.patch(`/trips/${tripId}/members/${memberId}/role`, { role })),
  remove: (tripId: string, memberId: string) =>
    unwrap<{ members: TripMember[] }>(http.delete(`/trips/${tripId}/members/${memberId}`)),
};

// ---------- days & places ----------
export const daysApi = {
  generate: (tripId: string) =>
    unwrap<{ days: TripDay[] }>(http.post(`/trips/${tripId}/days/generate`)),
  list: (tripId: string) => unwrap<{ days: TripDay[] }>(http.get(`/trips/${tripId}/days`)),
  update: (tripId: string, dayId: string, payload: Record<string, unknown>) =>
    unwrap<{ day: TripDay }>(http.patch(`/trips/${tripId}/days/${dayId}`, payload)),
};

export const placesApi = {
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ place: TripPlace }>(http.post(`/trips/${tripId}/places`, payload)),
  update: (tripId: string, placeId: string, payload: Record<string, unknown>) =>
    unwrap<{ place: TripPlace }>(http.patch(`/trips/${tripId}/places/${placeId}`, payload)),
  move: (tripId: string, placeId: string, tripDayId: string) =>
    unwrap<{ place: TripPlace }>(
      http.patch(`/trips/${tripId}/places/${placeId}/move`, { tripDayId })),
  remove: (tripId: string, placeId: string) =>
    unwrap(http.delete(`/trips/${tripId}/places/${placeId}`)),
  reorder: (tripId: string, dayId: string, placeIds: string[]) =>
    unwrap<{ places: TripPlace[] }>(
      http.patch(`/trips/${tripId}/days/${dayId}/places/reorder`, { placeIds })),
  suggestions: (keyword: string, city?: string) =>
    unwrap<{ suggestions: PlaceSuggestion[] }>(
      http.get('/places/suggestions', { params: { keyword, city } })),
};

// ---------- expenses ----------
export const expensesApi = {
  list: (tripId: string) =>
    unwrap<{ expenses: TripExpense[]; summary: Record<string, unknown> }>(
      http.get(`/trips/${tripId}/expenses`)),
  summary: (tripId: string) =>
    unwrap<ExpenseSummary>(http.get(`/trips/${tripId}/expenses/summary`)),
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ expense: TripExpense }>(http.post(`/trips/${tripId}/expenses`, payload)),
  update: (tripId: string, expenseId: string, payload: Record<string, unknown>) =>
    unwrap<{ expense: TripExpense }>(
      http.patch(`/trips/${tripId}/expenses/${expenseId}`, payload)),
  remove: (tripId: string, expenseId: string) =>
    unwrap(http.delete(`/trips/${tripId}/expenses/${expenseId}`)),
};

// ---------- packing lists ----------
export const packingApi = {
  list: (tripId: string) =>
    unwrap<{ packingLists: PackingList[] }>(http.get(`/trips/${tripId}/packing-lists`)),
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ packingList: PackingList }>(http.post(`/trips/${tripId}/packing-lists`, payload)),
  update: (tripId: string, listId: string, payload: Record<string, unknown>) =>
    unwrap<{ packingList: PackingList }>(
      http.patch(`/trips/${tripId}/packing-lists/${listId}`, payload)),
  remove: (tripId: string, listId: string) =>
    unwrap(http.delete(`/trips/${tripId}/packing-lists/${listId}`)),
  duplicate: (tripId: string, listId: string) =>
    unwrap<{ packingList: PackingList }>(
      http.post(`/trips/${tripId}/packing-lists/${listId}/duplicate`)),
  createItem: (tripId: string, listId: string, payload: Record<string, unknown>) =>
    unwrap<{ item: PackingList['items'][number] }>(
      http.post(`/trips/${tripId}/packing-lists/${listId}/items`, payload)),
  updateItem: (tripId: string, listId: string, itemId: string, payload: Record<string, unknown>) =>
    unwrap<{ item: PackingList['items'][number] }>(
      http.patch(`/trips/${tripId}/packing-lists/${listId}/items/${itemId}`, payload)),
  removeItem: (tripId: string, listId: string, itemId: string) =>
    unwrap(http.delete(`/trips/${tripId}/packing-lists/${listId}/items/${itemId}`)),
};

// ---------- photos ----------
export const photosApi = {
  list: (tripId: string) => unwrap<{ photos: Photo[] }>(http.get(`/trips/${tripId}/photos`)),
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ photo: Photo }>(http.post(`/trips/${tripId}/photos`, payload)),
  update: (tripId: string, photoId: string, payload: Record<string, unknown>) =>
    unwrap<{ photo: Photo }>(http.patch(`/trips/${tripId}/photos/${photoId}`, payload)),
  remove: (tripId: string, photoId: string) =>
    unwrap(http.delete(`/trips/${tripId}/photos/${photoId}`)),
};

// ---------- documents ----------
export const documentsApi = {
  list: (tripId: string) =>
    unwrap<{ documents: TravelDocument[] }>(http.get(`/trips/${tripId}/documents`)),
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ document: TravelDocument }>(http.post(`/trips/${tripId}/documents`, payload)),
  update: (tripId: string, documentId: string, payload: Record<string, unknown>) =>
    unwrap<{ document: TravelDocument }>(
      http.patch(`/trips/${tripId}/documents/${documentId}`, payload)),
  remove: (tripId: string, documentId: string) =>
    unwrap(http.delete(`/trips/${tripId}/documents/${documentId}`)),
};

// ---------- journals ----------
export const journalsApi = {
  list: (tripId: string) =>
    unwrap<{ journals: TripJournal[] }>(http.get(`/trips/${tripId}/journals`)),
  detail: (tripId: string, journalId: string) =>
    unwrap<{ journal: TripJournal }>(http.get(`/trips/${tripId}/journals/${journalId}`)),
  create: (tripId: string, payload: Record<string, unknown>) =>
    unwrap<{ journal: TripJournal }>(http.post(`/trips/${tripId}/journals`, payload)),
  update: (tripId: string, journalId: string, payload: Record<string, unknown>) =>
    unwrap<{ journal: TripJournal }>(http.patch(`/trips/${tripId}/journals/${journalId}`, payload)),
  remove: (tripId: string, journalId: string) =>
    unwrap(http.delete(`/trips/${tripId}/journals/${journalId}`)),
  reorderPhotos: (tripId: string, journalId: string, photoIds: string[]) =>
    unwrap<{ journal: TripJournal }>(
      http.patch(`/trips/${tripId}/journals/${journalId}/photos/reorder`, { photoIds })),
};

// ---------- notifications ----------
export const notificationsApi = {
  list: (page = 1, pageSize = 20) =>
    unwrap<{
      notifications: AppNotification[];
      total: number;
      page: number;
      pageSize: number;
      totalPages: number;
    }>(http.get('/notifications', { params: { page, pageSize } })),
  unreadCount: () => unwrap<{ count: number }>(http.get('/notifications/unread-count')),
  markRead: (notificationId: string) =>
    unwrap(http.patch(`/notifications/${notificationId}/read`)),
  markAllRead: () => unwrap(http.post('/notifications/read-all')),
  clearRead: () => unwrap(http.delete('/notifications/clear-read')),
};

// ---------- weather ----------
export const weatherApi = {
  tripWeather: (tripId: string) =>
    unwrap<{ source: string; weather: WeatherRow[] | null; message?: string }>(
      http.get(`/trips/${tripId}/weather`)),
};

// ---------- stats ----------
export const statsApi = {
  overview: () => unwrap<Record<string, unknown>>(http.get('/stats/overview')),
  yearly: (year?: number) =>
    unwrap<Record<string, unknown>>(http.get('/stats/yearly', { params: { year } })),
  monthly: (year?: number) =>
    unwrap<Record<string, unknown>>(http.get('/stats/monthly', { params: { year } })),
  expenseCategories: () =>
    unwrap<Record<string, unknown>>(http.get('/stats/expense-categories')),
};

// ---------- pdf ----------
export async function exportTripPdf(tripId: string): Promise<void> {
  const response = await http.get(`/trips/${tripId}/export/pdf`, { responseType: 'blob' });
  const disposition = response.headers['content-disposition'] as string | undefined;
  let filename = '行程手册.pdf';
  if (disposition && disposition.includes("''")) {
    filename = decodeURIComponent(disposition.split("''")[1]);
  }
  const url = URL.createObjectURL(response.data as Blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export { errorMessage };
