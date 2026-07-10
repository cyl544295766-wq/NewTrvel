import L, { LatLngExpression } from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Fragment, useEffect, useMemo } from 'react';
import { MapContainer, Marker, Polyline, Popup, TileLayer, useMap } from 'react-leaflet';
import { TripRouteDay, TripRoutePlaceType } from '../types/map.types';

const dayColors = ['#ef4444', '#3b82f6', '#22c55e', '#f59e0b', '#8b5cf6', '#ec4899'];

const typeLabels: Record<TripRoutePlaceType, string> = {
  attraction: '景点',
  hotel: '酒店',
  restaurant: '餐厅',
  transport: '交通',
  shopping: '购物',
  custom: '自定义',
};

type Props = {
  route: TripRouteDay[];
};

export function TripMap({ route }: Props) {
  const routeDays = useMemo(
    () =>
      route
        .map((day) => ({
          ...day,
          places: day.places
            .map((place) => ({
              ...place,
              position: [Number(place.latitude), Number(place.longitude)] as LatLngExpression,
            }))
            .filter(
              (place) =>
                Number.isFinite(Number(place.latitude)) && Number.isFinite(Number(place.longitude)),
            ),
        }))
        .filter((day) => day.places.length > 0),
    [route],
  );
  const bounds = routeDays.flatMap((day) => day.places.map((place) => place.position));

  if (bounds.length === 0) {
    return <p className="empty-state">该旅行暂无地点坐标，去行程规划添加地址吧</p>;
  }

  return (
    <div className="trip-map-shell">
      <MapContainer center={bounds[0]} className="trip-map" zoom={12} scrollWheelZoom>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <FitBounds positions={bounds} />
        {routeDays.map((day) => {
          const color = dayColors[(day.dayIndex - 1) % dayColors.length];
          const positions = day.places.map((place) => place.position);

          return (
            <Fragment key={day.dayId}>
              {positions.length > 1 ? <Polyline color={color} positions={positions} /> : null}
              {day.places.map((place, index) => (
                <Marker
                  icon={createMarkerIcon(color, index + 1)}
                  key={place.id}
                  position={place.position}
                >
                  <Popup>
                    <strong>{place.name}</strong>
                    <p>{typeLabels[place.type]}</p>
                    <p>第 {day.dayIndex} 天</p>
                  </Popup>
                </Marker>
              ))}
            </Fragment>
          );
        })}
      </MapContainer>
    </div>
  );
}

function FitBounds({ positions }: { positions: LatLngExpression[] }) {
  const map = useMap();

  useEffect(() => {
    if (positions.length === 1) {
      map.setView(positions[0], 13);
      return;
    }

    map.fitBounds(L.latLngBounds(positions), { padding: [32, 32] });
  }, [map, positions]);

  return null;
}

function createMarkerIcon(color: string, label: number) {
  return L.divIcon({
    className: 'route-marker',
    html: `<span style="background:${color}">${label}</span>`,
    iconAnchor: [14, 14],
    popupAnchor: [0, -14],
  });
}

export { dayColors };
