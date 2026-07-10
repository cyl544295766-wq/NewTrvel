import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { TripStatusBadge } from '../components/TripStatusBadge';
import {
  useArchiveTrip,
  useDeleteTrip,
  useDuplicateTrip,
  useFavoriteTrip,
  useTrip,
} from '../hooks/useTrips';

export function TripDetailPage() {
  const navigate = useNavigate();
  const { tripId } = useParams<{ tripId: string }>();
  const trip = useTrip(tripId ?? '', Boolean(tripId));
  const archiveTrip = useArchiveTrip();
  const duplicateTrip = useDuplicateTrip();
  const favoriteTrip = useFavoriteTrip();
  const deleteTrip = useDeleteTrip();
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
  const data = trip.data?.trip;
  const canManage = data?.currentUserRole === 'owner' || data?.currentUserRole === 'admin';

  if (!tripId) {
    return <Navigate replace to="/" />;
  }

  if (trip.isLoading) {
    return <main className="loading-shell">Loading...</main>;
  }

  if (trip.isError || !data) {
    return <Navigate replace to="/" />;
  }

  async function handleDelete() {
    if (!data) {
      return;
    }

    await deleteTrip.mutateAsync(data.id);
    setIsDeleteDialogOpen(false);
    navigate('/', { replace: true });
  }

  return (
    <main className="app-page narrow-page">
      <Link className="text-link" to="/">
        Back to trips
      </Link>
      <section className="content-panel detail-panel">
        <div className="detail-heading">
          <div>
            <p className="eyebrow">Trip Detail</p>
            <h1>
              <button
                aria-label={data.isFavorite ? 'Unfavorite trip' : 'Favorite trip'}
                className={data.isFavorite ? 'icon-button favorite active' : 'icon-button favorite'}
                disabled={favoriteTrip.isPending}
                onClick={() => {
                  void favoriteTrip.mutateAsync(data.id);
                }}
                type="button"
              >
                ★
              </button>
              {data.title}
            </h1>
          </div>
          <TripStatusBadge status={data.status} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>Destination</dt>
            <dd>{data.destination || 'Not set'}</dd>
          </div>
          <div>
            <dt>Dates</dt>
            <dd>{formatDateRange(data.startDate, data.endDate)}</dd>
          </div>
          <div>
            <dt>Your role</dt>
            <dd>{data.currentUserRole}</dd>
          </div>
          <div>
            <dt>Updated</dt>
            <dd>{new Date(data.updatedAt).toLocaleString()}</dd>
          </div>
        </dl>
        <p>{data.description || 'No description yet.'}</p>
        <div className="detail-actions">
          {canManage ? (
            <Link className="button-link" to={`/trips/${data.id}/edit`}>
              Edit
            </Link>
          ) : null}
          <Link className="button-link" to={`/trips/${data.id}/itinerary`}>
            Itinerary
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/expenses`}>
            Expenses
          </Link>
          {canManage ? (
            <>
              <button
                className="secondary-button"
                disabled={duplicateTrip.isPending}
                onClick={() => {
                  void duplicateTrip.mutateAsync(data.id);
                }}
                type="button"
              >
                Duplicate
              </button>
              <button
                className="secondary-button"
                disabled={archiveTrip.isPending}
                onClick={() => {
                  void archiveTrip.mutateAsync(data.id);
                  navigate('/', { replace: true });
                }}
                type="button"
              >
                Archive
              </button>
              <button
                className="secondary-button danger-button"
                disabled={deleteTrip.isPending}
                onClick={() => setIsDeleteDialogOpen(true)}
                type="button"
              >
                Delete
              </button>
            </>
          ) : null}
        </div>
      </section>
      <ConfirmDialog
        confirmLabel="Delete"
        content={`"${data.title}" will be hidden from all trip lists and detail views.`}
        isOpen={isDeleteDialogOpen}
        isPending={deleteTrip.isPending}
        onCancel={() => setIsDeleteDialogOpen(false)}
        onConfirm={() => {
          void handleDelete();
        }}
        title="Delete trip?"
      />
    </main>
  );
}

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return 'Not set';
  }

  return [startDate?.slice(0, 10), endDate?.slice(0, 10)].filter(Boolean).join(' to ');
}
