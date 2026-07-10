import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { LoginPage, ProtectedRoute } from '../features/auth';
import { ExpensesPage } from '../features/expenses';
import { ItineraryPage } from '../features/itinerary';
import { MapPage } from '../features/map';
import { PackingListsPage } from '../features/packing-lists';
import { PhotosPage } from '../features/photos';
import { TravelDocumentsPage } from '../features/travel-documents';
import { JournalDetailPage, TripJournalsPage } from '../features/trip-journals';
import { EditTripPage, NewTripPage, TripDetailPage } from '../features/trips';
import { HomePage } from '../pages/HomePage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <HomePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/new"
          element={
            <ProtectedRoute>
              <NewTripPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId"
          element={
            <ProtectedRoute>
              <TripDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/edit"
          element={
            <ProtectedRoute>
              <EditTripPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/itinerary"
          element={
            <ProtectedRoute>
              <ItineraryPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/expenses"
          element={
            <ProtectedRoute>
              <ExpensesPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/map"
          element={
            <ProtectedRoute>
              <MapPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/photos"
          element={
            <ProtectedRoute>
              <PhotosPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/documents"
          element={
            <ProtectedRoute>
              <TravelDocumentsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/packing-lists"
          element={
            <ProtectedRoute>
              <PackingListsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/journals"
          element={
            <ProtectedRoute>
              <TripJournalsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/trips/:tripId/journals/:journalId"
          element={
            <ProtectedRoute>
              <JournalDetailPage />
            </ProtectedRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}
