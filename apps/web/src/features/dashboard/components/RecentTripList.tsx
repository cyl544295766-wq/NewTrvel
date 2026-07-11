import { Link } from 'react-router-dom';
import { DashboardPhoto, DashboardTrip } from '../types/dashboard.types';

type RecentTripListProps = {
  photos?: DashboardPhoto[];
  trips: DashboardTrip[];
};

export function RecentTripList({ photos = [], trips }: RecentTripListProps) {
  return (
    <section className="dashboard-trip-film" aria-label="最近旅行">
      {trips.length === 0 ? (
        <p className="dashboard-film-empty">暂无旅行，创建一段新旅程后会在这里展示。</p>
      ) : (
        <div className="dashboard-film-track">
          {trips.map((trip) => (
            <Link className="dashboard-film-item" key={trip.id} to={`/trips/${trip.id}`}>
              <img
                alt={`${trip.destination || trip.title}旅行缩略图`}
                src={photos.find((photo) => photo.tripId === trip.id)?.thumbnailUrl ?? '/login-travel.webp'}
              />
              <span>
                <strong>{trip.title}</strong>
                <small>{trip.destination || formatDate(trip.updatedAt)}</small>
              </span>
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
