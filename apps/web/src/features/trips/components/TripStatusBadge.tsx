import { TripStatus } from '../types/trip.types';

type TripStatusBadgeProps = {
  status: TripStatus;
};

export function TripStatusBadge({ status }: TripStatusBadgeProps) {
  return <span className={`status-badge status-${status}`}>{status}</span>;
}
