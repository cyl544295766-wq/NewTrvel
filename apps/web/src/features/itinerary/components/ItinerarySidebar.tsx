import { CalendarDays, Map, MapPin, Plus, Route, Sparkles } from 'lucide-react';
import { Link } from 'react-router-dom';
import { ExportPdfButton } from '../../trip-pdf';
import { WeatherCard } from '../../weather/components/WeatherCard';
import { TripWeather } from '../../weather';
import { Trip } from '../../trips/types/trip.types';
import { TripDay } from '../types/itinerary.types';

type Props = {
  trip: Trip;
  days: TripDay[];
  weather: TripWeather[];
  activeDayId: string | null;
  completedPlaces: number;
  totalPlaces: number;
  onDaySelect: (dayId: string) => void;
  onAddPlace: () => void;
};

export function ItinerarySidebar({ trip, days, weather, activeDayId, completedPlaces, totalPlaces, onDaySelect, onAddPlace }: Props) {
  const progress = totalPlaces ? Math.round((completedPlaces / totalPlaces) * 100) : 0;
  return (
    <aside className="itinerary-sidebar">
      <div className="itinerary-sidebar-scroll">
        <header className="itinerary-trip-summary">
          <div className="itinerary-cover">{trip.coverImageUrl ? <img alt={`${trip.title}封面`} src={trip.coverImageUrl} /> : <Route size={28} />}</div>
          <p className="itinerary-overline">TRAVEL NOTEBOOK</p>
          <h2>{trip.title}</h2>
          <p className="itinerary-trip-meta"><MapPin size={14} />{trip.destination || '目的地待定'}</p>
          <p className="itinerary-trip-date">{formatRange(trip.startDate, trip.endDate)}</p>
        </header>

        <section className="itinerary-stats" aria-label="行程统计">
          <div><strong>{days.length}</strong><span>总天数</span></div><div><strong>{totalPlaces}</strong><span>地点</span></div><div><strong>{completedPlaces}</strong><span>已完成</span></div>
          <div className="itinerary-progress"><div><span>行程进度</span><b>{progress}%</b></div><span className="progress-track"><i style={{ width: `${progress}%` }} /></span></div>
        </section>

        {weather.length ? <section className="itinerary-weather"><div className="itinerary-section-label"><Sparkles size={14} />未来天气</div><div className="itinerary-weather-list">{weather.slice(0, 3).map((item) => <WeatherCard key={`${item.tripId}-${item.date}`} weather={item} />)}</div></section> : null}

        <nav className="itinerary-day-nav" aria-label="日期快速导航"><div className="itinerary-section-label"><CalendarDays size={14} />行程日期</div>{days.map((day) => <button aria-current={activeDayId === day.id ? 'step' : undefined} className={activeDayId === day.id ? 'is-active' : ''} key={day.id} onClick={() => onDaySelect(day.id)} type="button"><span>Day {day.dayIndex}</span><small>{formatDayDate(day.date)}</small><b>{day.places.length}</b></button>)}</nav>
      </div>
      <footer className="itinerary-sidebar-footer"><button className="itinerary-add-button" onClick={onAddPlace} type="button"><Plus size={17} />添加地点</button><Link className="itinerary-map-link" to={`/trips/${trip.id}/map`}><Map size={16} />打开完整地图</Link><ExportPdfButton compact tripId={trip.id} /></footer>
    </aside>
  );
}

function formatRange(start: string | null, end: string | null) { if (!start && !end) return '日期待定'; return `${start?.slice(0, 10) ?? '待定'} — ${end?.slice(0, 10) ?? '待定'}`; }
function formatDayDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', weekday: 'short' }).format(new Date(value)); }
