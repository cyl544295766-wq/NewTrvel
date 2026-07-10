import { Link } from 'react-router-dom';
import { DashboardTrip } from '../types/dashboard.types';

type RecentTripListProps = {
  trips: DashboardTrip[];
};

export function RecentTripList({ trips }: RecentTripListProps) {
  return (
    <section className="content-panel dashboard-card">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">旅行</p>
          <h2>最近旅行</h2>
        </div>
      </div>
      {trips.length === 0 ? (
        <p className="empty-state">暂无旅行，去创建一个吧</p>
      ) : (
        <div className="dashboard-list">
          {trips.map((trip) => (
            <Link className="dashboard-list-row" key={trip.id} to={`/trips/${trip.id}`}>
              <div>
                <strong>
                  {trip.isFavorite ? <span aria-hidden="true">★ </span> : null}
                  {trip.title}
                </strong>
                <span>{trip.destination || '目的地未设置'}</span>
              </div>
              <time>{formatDate(trip.updatedAt)}</time>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}
