import { Link } from 'react-router-dom';
import { DashboardTrip } from '../types/dashboard.types';

type UpcomingTripListProps = {
  trips: DashboardTrip[];
};

export function UpcomingTripList({ trips }: UpcomingTripListProps) {
  return (
    <section className="dashboard-upcoming-trips" aria-label="即将出发的旅行">
      {trips.length === 0 ? (
        <p className="dashboard-upcoming-empty">近期没有即将出发的旅行</p>
      ) : (
        <div className="dashboard-upcoming-track">
          {trips.map((trip) => (
            <Link className="dashboard-upcoming-card" key={trip.id} to={`/trips/${trip.id}`}>
              <span className="dashboard-upcoming-countdown">{getCountdown(trip.startDate)}</span>
              <div>
                <strong>{trip.title}</strong>
                <span>{trip.destination || '目的地未设置'}</span>
              </div>
              <time>{formatDateRange(trip.startDate, trip.endDate)}</time>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function getCountdown(startDate: string | null) {
  if (!startDate) return '日期待定';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const start = new Date(startDate);
  start.setHours(0, 0, 0, 0);
  const days = Math.round((start.getTime() - today.getTime()) / 86_400_000);
  if (days === 0) return '今天出发';
  return days > 0 ? `${days} 天后` : '行程进行中';
}

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return '日期未设置';
  }

  return [startDate?.slice(0, 10), endDate?.slice(0, 10)].filter(Boolean).join(' 至 ');
}
