import {
  ArrowLeft,
  BedDouble,
  Bus,
  CalendarDays,
  MapPin,
  MapPinned,
  ShoppingBag,
  Sparkles,
  Utensils,
} from 'lucide-react';
import { ComponentType, useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { hasValidCoordinates } from './TripMap';
import { TripRouteDay, TripRoutePlace, TripRoutePlaceType } from '../types/map.types';

type Props = {
  activeDayId: string | null;
  activePlaceType: TripRoutePlaceType | 'all';
  missingPlaceCount: number;
  onDayChange: (dayId: string | null) => void;
  onPlaceTypeChange: (type: TripRoutePlaceType | 'all') => void;
  onSelectPlace: (placeId: string | null) => void;
  route: TripRouteDay[];
  selectedPlaceId: string | null;
  tripId: string;
};

const placeTypes: Array<{
  type: TripRoutePlaceType | 'all';
  label: string;
  icon: ComponentType<{ size?: number }>;
}> = [
  { type: 'all', label: '全部', icon: Sparkles },
  { type: 'attraction', label: '景点', icon: MapPinned },
  { type: 'hotel', label: '酒店', icon: BedDouble },
  { type: 'restaurant', label: '餐厅', icon: Utensils },
  { type: 'transport', label: '交通', icon: Bus },
  { type: 'shopping', label: '购物', icon: ShoppingBag },
  { type: 'custom', label: '其他', icon: MapPin },
];

const typeLabels: Record<TripRoutePlaceType, string> = {
  attraction: '景点', hotel: '酒店', restaurant: '餐厅', transport: '交通', shopping: '购物', custom: '其他',
};

export function MapSidebar(props: Props) {
  const visibleDays = useMemo(
    () =>
      props.route
        .filter((day) => !props.activeDayId || day.dayId === props.activeDayId)
        .map((day) => ({
          ...day,
          places: day.places
            .filter(hasValidCoordinates)
            .filter((place) => props.activePlaceType === 'all' || place.type === props.activePlaceType),
        }))
        .filter((day) => day.places.length > 0),
    [props.activeDayId, props.activePlaceType, props.route],
  );
  const validPlaceCount = props.route.flatMap((day) => day.places).filter(hasValidCoordinates).length;

  useEffect(() => {
    if (!props.selectedPlaceId) return;
    document.querySelector(`[data-map-place-id="${props.selectedPlaceId}"]`)?.scrollIntoView({
      behavior: 'smooth', block: 'nearest',
    });
  }, [props.selectedPlaceId]);

  return (
    <aside className="map-sidebar">
      <header className="map-sidebar-header">
        <Link className="map-back-link" to={`/trips/${props.tripId}`}><ArrowLeft size={16} />返回旅行详情</Link>
        <p>ROUTE OVERVIEW</p>
        <h1>旅行地图</h1>
        <span>{props.route.length} 天 · {validPlaceCount} 个地图点位</span>
      </header>

      <nav className="map-day-filter" aria-label="按日期筛选路线">
        <button aria-pressed={!props.activeDayId} className={!props.activeDayId ? 'is-active' : ''} onClick={() => props.onDayChange(null)} type="button">全部</button>
        {props.route.map((day) => (
          <button aria-pressed={props.activeDayId === day.dayId} className={props.activeDayId === day.dayId ? 'is-active' : ''} key={day.dayId} onClick={() => props.onDayChange(day.dayId)} type="button"><span>D{day.dayIndex}</span><small>{formatShortDate(day.date)}</small></button>
        ))}
      </nav>

      <div className="map-type-filter" aria-label="按地点类型筛选">
        {placeTypes.map(({ type, label, icon: Icon }) => (
          <button aria-label={label} aria-pressed={props.activePlaceType === type} className={props.activePlaceType === type ? 'is-active' : ''} key={type} onClick={() => props.onPlaceTypeChange(type)} title={label} type="button"><Icon size={16} /><span>{label}</span></button>
        ))}
      </div>

      <div className="map-place-scroll">
        {visibleDays.length ? visibleDays.map((day) => (
          <section className="map-day-group" key={day.dayId}>
            <header><div><CalendarDays size={14} /><strong>第 {day.dayIndex} 天</strong></div><span>{formatFullDate(day.date)}</span></header>
            <ol>
              {day.places.map((place) => (
                <MapPlaceRow day={day} isSelected={props.selectedPlaceId === place.id} key={place.id} onSelect={props.onSelectPlace} place={place} />
              ))}
            </ol>
          </section>
        )) : (
          <div className="map-filter-empty"><MapPin size={24} /><strong>当前筛选下没有点位</strong><p>尝试切换日期或地点类型。</p><button onClick={() => { props.onDayChange(null); props.onPlaceTypeChange('all'); }} type="button">查看全部路线</button></div>
        )}
      </div>

      {props.missingPlaceCount > 0 ? <footer className="map-missing-note"><MapPin size={15} /><span>{props.missingPlaceCount} 个地点缺少坐标，暂未显示</span><Link to={`/trips/${props.tripId}/itinerary`}>前往补充</Link></footer> : null}
    </aside>
  );
}

function MapPlaceRow({ day, isSelected, onSelect, place }: { day: TripRouteDay; isSelected: boolean; onSelect: (id: string | null) => void; place: TripRoutePlace }) {
  return (
    <li>
      <button aria-current={isSelected ? 'location' : undefined} className={isSelected ? 'is-selected' : ''} data-map-place-id={place.id} onClick={() => onSelect(isSelected ? null : place.id)} type="button">
        <span className="map-place-order">{place.sortOrder + 1}</span>
        <span className="map-place-copy"><strong>{place.name}</strong><small>{typeLabels[place.type]} · 第 {day.dayIndex} 天</small></span>
        <MapPin className="map-place-arrow" size={16} />
      </button>
    </li>
  );
}

function formatShortDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(value)); }
function formatFullDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(value)); }
