import { useMemo, useState } from 'react';
import { MapPinned, RefreshCw } from 'lucide-react';
import { Navigate, useParams } from 'react-router-dom';
import { MapSidebar } from '../components/MapSidebar';
import { hasValidCoordinates, TripMap } from '../components/TripMap';
import { useTripMap } from '../hooks/useTripMap';
import { TripRoutePlaceType } from '../types/map.types';

export function MapPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const route = useTripMap(safeTripId);
  const [activeDayId, setActiveDayId] = useState<string | null>(null);
  const [activePlaceType, setActivePlaceType] = useState<TripRoutePlaceType | 'all'>('all');
  const [selectedPlaceId, setSelectedPlaceId] = useState<string | null>(null);

  const routeDays = route.data?.route ?? [];
  const visibleRoute = useMemo(
    () =>
      routeDays
        .filter((day) => !activeDayId || day.dayId === activeDayId)
        .map((day) => ({
          ...day,
          places: day.places.filter(
            (place) => activePlaceType === 'all' || place.type === activePlaceType,
          ),
        })),
    [activeDayId, activePlaceType, routeDays],
  );
  const validPlaceCount = routeDays.flatMap((day) => day.places).filter(hasValidCoordinates).length;
  const missingPlaceCount = routeDays.flatMap((day) => day.places).length - validPlaceCount;

  if (!tripId) return <Navigate replace to="/" />;

  if (route.isLoading) {
    return (
      <main className="map-workspace-page map-loading-state" aria-label="地图加载中">
        <aside className="map-sidebar-skeleton"><i /><i /><i /><i /><i /></aside>
        <div className="map-canvas-skeleton"><span><MapPinned size={28} />正在整理旅行路线</span></div>
      </main>
    );
  }

  if (route.isError) {
    return (
      <main className="map-workspace-page map-state-page">
        <section className="map-state-panel">
          <MapPinned size={32} />
          <h1>地图路线加载失败</h1>
          <p>我们暂时无法读取这段旅行的地点，请重新加载。</p>
          <button onClick={() => route.refetch()} type="button"><RefreshCw size={17} />重新加载</button>
        </section>
      </main>
    );
  }

  return (
    <main className="map-workspace-page">
      <MapSidebar
        activeDayId={activeDayId}
        activePlaceType={activePlaceType}
        missingPlaceCount={missingPlaceCount}
        onDayChange={(dayId) => { setActiveDayId(dayId); setSelectedPlaceId(null); }}
        onPlaceTypeChange={(type) => { setActivePlaceType(type); setSelectedPlaceId(null); }}
        onSelectPlace={setSelectedPlaceId}
        route={routeDays}
        selectedPlaceId={selectedPlaceId}
        tripId={safeTripId}
      />
      <section className="map-canvas-region" aria-label="旅行路线地图">
        <TripMap
          activeDayId={activeDayId}
          onSelectPlace={setSelectedPlaceId}
          route={visibleRoute}
          selectedPlaceId={selectedPlaceId}
        />
      </section>
    </main>
  );
}
