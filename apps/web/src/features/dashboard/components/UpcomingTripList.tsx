import { Link } from 'react-router-dom';
import { DashboardTrip } from '../types/dashboard.types';

type UpcomingTripListProps = {
  trips: DashboardTrip[];
};

export function UpcomingTripList({ trips }: UpcomingTripListProps) {
  return (
    <section className="content-panel dashboard-card">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">计划</p>
          <h2>即将出发</h2>
        </div>
      </div>
      {trips.length === 0 ? (
        <p className="empty-state">近期没有即将出发的旅行</p>
      ) : (
        <div className="dashboard-list">
          {trips.map((trip) => (
            <Link className="dashboard-list-row" key={trip.id} to={`/trips/${trip.id}`}>
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

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return '日期未设置';
  }

  return [startDate?.slice(0, 10), endDate?.slice(0, 10)].filter(Boolean).join(' 至 ');
}
