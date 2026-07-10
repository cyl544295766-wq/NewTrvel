import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { ExportPdfButton } from '../../trip-pdf';
import { TripStatusBadge } from './TripStatusBadge';
import {
  useArchiveTrip,
  useDeleteTrip,
  useDuplicateTrip,
  useFavoriteTrip,
} from '../hooks/useTrips';
import { Trip } from '../types/trip.types';

type TripListItemProps = {
  trip: Trip;
};

export function TripListItem({ trip }: TripListItemProps) {
  const navigate = useNavigate();
  const archiveTrip = useArchiveTrip();
  const duplicateTrip = useDuplicateTrip();
  const favoriteTrip = useFavoriteTrip();
  const deleteTrip = useDeleteTrip();
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
  const canManage = trip.currentUserRole === 'owner' || trip.currentUserRole === 'admin';
  const isBusy =
    archiveTrip.isPending ||
    duplicateTrip.isPending ||
    favoriteTrip.isPending ||
    deleteTrip.isPending;

  async function handleAction(action: string) {
    if (!action) {
      return;
    }

    if (action === 'edit') {
      navigate(`/trips/${trip.id}/edit`);
      return;
    }

    if (action === 'archive') {
      await archiveTrip.mutateAsync(trip.id);
      return;
    }

    if (action === 'duplicate') {
      await duplicateTrip.mutateAsync(trip.id);
      return;
    }

    if (action === 'favorite') {
      await favoriteTrip.mutateAsync(trip.id);
      return;
    }

    if (action === 'delete') {
      setIsDeleteDialogOpen(true);
    }
  }

  async function handleDelete() {
    await deleteTrip.mutateAsync(trip.id);
    setIsDeleteDialogOpen(false);
  }

  return (
    <article className="trip-list-item">
      <Link className="trip-list-link" to={`/trips/${trip.id}`}>
        <div>
          <h2>
            <span aria-hidden="true" className={trip.isFavorite ? 'favorite-star active' : 'favorite-star'}>
              ★
            </span>
            {trip.title}
          </h2>
          <p>{trip.destination || 'No destination yet'}</p>
        </div>
        <div className="trip-list-meta">
          <TripStatusBadge status={trip.status} />
          <span>{formatDateRange(trip.startDate, trip.endDate)}</span>
          <span>{trip.currentUserRole}</span>
        </div>
      </Link>
      <ExportPdfButton compact tripId={trip.id} />
      <select
        aria-label={`More actions for ${trip.title}`}
        className="trip-action-menu"
        disabled={isBusy}
        onChange={(event) => {
          void handleAction(event.target.value);
          event.target.value = '';
        }}
        value=""
      >
        <option value="">More actions</option>
        <option value="edit">Edit</option>
        {canManage ? <option value="duplicate">Duplicate</option> : null}
        {canManage ? <option value="archive">Archive</option> : null}
        <option value="favorite">{trip.isFavorite ? 'Unfavorite' : 'Favorite'}</option>
        {canManage ? <option value="delete">Delete</option> : null}
      </select>
      <ConfirmDialog
        confirmLabel="Delete"
        content={`"${trip.title}" will be hidden from all trip lists and detail views.`}
        isOpen={isDeleteDialogOpen}
        isPending={deleteTrip.isPending}
        onCancel={() => setIsDeleteDialogOpen(false)}
        onConfirm={() => {
          void handleDelete();
        }}
        title="Delete trip?"
      />
    </article>
  );
}

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return 'No dates yet';
  }

  return [startDate?.slice(0, 10), endDate?.slice(0, 10)].filter(Boolean).join(' to ');
}
