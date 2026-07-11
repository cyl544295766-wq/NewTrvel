import { CalendarDays, MapPin, Plus } from 'lucide-react';
import { Link } from 'react-router-dom';
import { DashboardPhoto, DashboardTrip } from '../types/dashboard.types';

type FeaturedTripProps = {
  trip?: DashboardTrip;
  photo?: DashboardPhoto;
};

export function FeaturedTrip({ photo, trip }: FeaturedTripProps) {
  if (!trip) {
    return (
      <section className="dashboard-focus dashboard-focus-empty">
        <div>
          <p className="eyebrow">下一段旅行</p>
          <h2>从一个目的地开始</h2>
          <p>建立旅行后，可以在这里继续安排日程、费用和出行资料。</p>
        </div>
        <Link className="button-link" to="/trips/new">
          <Plus size={17} />
          新建旅行
        </Link>
      </section>
    );
  }

  return (
    <section className={`dashboard-focus${photo ? ' dashboard-focus-with-photo' : ''}`}>
      <img
        alt={photo?.alt ?? `${trip.destination || trip.title}旅行风景`}
        className="dashboard-focus-media"
        src={photo?.thumbnailUrl ?? '/login-travel.webp'}
      />
      <div className="dashboard-focus-topline">
        <p className="eyebrow">下一段旅行</p>
        <span>{getDepartureLabel(trip)}</span>
      </div>

      <div className="dashboard-focus-copy">
        <h2>{trip.title}</h2>
        <div className="dashboard-focus-meta">
          <span>
            <MapPin size={16} />
            {trip.destination || '目的地未设置'}
          </span>
          <span>
            <CalendarDays size={16} />
            {formatDateRange(trip.startDate, trip.endDate)}
          </span>
        </div>
      </div>
    </section>
  );
}

function getDepartureLabel(trip: DashboardTrip) {
  if (!trip.startDate) {
    return '日期待确定';
  }

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const startDate = new Date(trip.startDate);
  startDate.setHours(0, 0, 0, 0);
  const days = Math.round((startDate.getTime() - today.getTime()) / 86_400_000);

  if (days > 0) return `${days} 天后出发`;
  if (days === 0) return '今天出发';
  return '行程已开始';
}

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) return '日期未设置';
  return [formatDate(startDate), formatDate(endDate)].filter(Boolean).join(' 至 ');
}

function formatDate(value: string | null) {
  if (!value) return null;
  return new Date(value).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' });
}
