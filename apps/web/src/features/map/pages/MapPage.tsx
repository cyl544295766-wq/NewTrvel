import { Link, Navigate, useParams } from 'react-router-dom';
import { TripMap, dayColors } from '../components/TripMap';
import { useTripMap } from '../hooks/useTripMap';

export function MapPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const route = useTripMap(safeTripId);
  const routeDays = route.data?.route.filter((day) => day.places.length > 0) ?? [];

  if (!tripId) return <Navigate replace to="/" />;

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">地图</p>
            <h1>地图路线</h1>
          </div>
        </div>
        {route.isLoading ? <p>加载中...</p> : null}
        {route.isError ? <p className="form-error">地图路线加载失败</p> : null}
        {route.data ? (
          <>
            <TripMap route={routeDays} />
            {routeDays.length > 0 ? (
              <div className="map-legend">
                {routeDays.map((day) => (
                  <span key={day.dayId}>
                    <i style={{ background: dayColors[(day.dayIndex - 1) % dayColors.length] }} />第{' '}
                    {day.dayIndex} 天
                  </span>
                ))}
              </div>
            ) : null}
          </>
        ) : null}
      </section>
    </main>
  );
}
