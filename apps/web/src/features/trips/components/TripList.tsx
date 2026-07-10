import { TripListItem } from './TripListItem';
import { Trip } from '../types/trip.types';

type TripListProps = {
  trips: Trip[];
};

export function TripList({ trips }: TripListProps) {
  if (trips.length === 0) {
    return <p className="empty-state">No trips yet.</p>;
  }

  return (
    <div className="trip-list">
      {trips.map((trip) => (
        <TripListItem key={trip.id} trip={trip} />
      ))}
    </div>
  );
}
