import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import AppShell from '../layouts/AppShell.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../pages/LoginPage.vue') },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', name: 'dashboard', component: () => import('../pages/DashboardPage.vue') },
        { path: 'stats', name: 'stats', component: () => import('../pages/StatsPage.vue') },
        { path: 'notifications', name: 'notifications', component: () => import('../pages/NotificationsPage.vue') },
        { path: 'account', name: 'account', component: () => import('../pages/AccountPage.vue') },
        { path: 'trips', name: 'trips', component: () => import('../pages/TripsPage.vue') },
        { path: 'trips/new', name: 'trips-new', component: () => import('../pages/NewTripPage.vue') },
        { path: 'trips/:tripId', name: 'trip-detail', component: () => import('../pages/TripDetailPage.vue') },
        { path: 'trips/:tripId/edit', name: 'trip-edit', component: () => import('../pages/EditTripPage.vue') },
        { path: 'trips/:tripId/itinerary', name: 'itinerary', component: () => import('../pages/ItineraryPage.vue') },
        { path: 'trips/:tripId/expenses', name: 'expenses', component: () => import('../pages/ExpensesPage.vue') },
        { path: 'trips/:tripId/map', name: 'map', component: () => import('../pages/MapPage.vue') },
        { path: 'trips/:tripId/photos', name: 'photos', component: () => import('../pages/PhotosPage.vue') },
        { path: 'trips/:tripId/documents', name: 'documents', component: () => import('../pages/DocumentsPage.vue') },
        { path: 'trips/:tripId/packing-lists', name: 'packing', component: () => import('../pages/PackingListsPage.vue') },
        { path: 'trips/:tripId/bookings', name: 'bookings', component: () => import('../pages/BookingsPage.vue') },
        { path: 'trips/:tripId/vouchers', name: 'vouchers', component: () => import('../pages/VouchersPage.vue') },
        { path: 'trips/:tripId/journals', name: 'journals', component: () => import('../pages/JournalsPage.vue') },
        { path: 'trips/:tripId/journals/:journalId', name: 'journal-detail', component: () => import('../pages/JournalDetailPage.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue') },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  await auth.init();
  if (to.name !== 'login' && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'dashboard' };
  }
  return true;
});
