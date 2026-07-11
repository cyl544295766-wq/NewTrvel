import { useDroppable } from '@dnd-kit/core';
import { CalendarDays, FormInput, Plus } from 'lucide-react';
import { FormEvent, useEffect, useState } from 'react';
import { TripWeather, WeatherBadge } from '../../weather';
import { TripDay, TripPlace, TripPlaceInput } from '../types/itinerary.types';
import { TripPlaceForm } from './TripPlaceForm';
import { TripPlaceList } from './TripPlaceList';

type Props = {
  day: TripDay;
  canEdit: boolean;
  onUpdateDay: (dayId: string, title: string, summary: string) => Promise<void>;
  onCreatePlace: (input: TripPlaceInput) => Promise<void>;
  onDeletePlace: (placeId: string) => void;
  onTogglePlaceCompleted: (place: TripPlace) => void;
  isCreatingPlace: boolean;
  weather?: TripWeather;
  searchCity?: string;
  selectedPlaceId?: string | null;
  onSelectPlace?: (placeId: string) => void;
  onUpdatePlace?: (placeId: string, input: Partial<TripPlaceInput>) => Promise<void>;
  openForm?: boolean;
  onFormOpenChange?: (open: boolean) => void;
};

export function TripDayCard({ day, canEdit, onUpdateDay, onCreatePlace, onDeletePlace, onTogglePlaceCompleted, isCreatingPlace, weather, searchCity, selectedPlaceId, onSelectPlace, onUpdatePlace, openForm = false, onFormOpenChange }: Props) {
  const [title, setTitle] = useState(day.title ?? '');
  const [summary, setSummary] = useState(day.summary ?? '');
  const [isFormOpen, setIsFormOpen] = useState(openForm);
  const { isOver, setNodeRef } = useDroppable({ id: `day:${day.id}`, data: { dayId: day.id } });

  useEffect(() => { if (openForm) setIsFormOpen(true); }, [openForm]);

  function toggleForm(next: boolean) { setIsFormOpen(next); onFormOpenChange?.(next); }
  async function handleSubmit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); await onUpdateDay(day.id, title, summary); }

  return (
    <section className={`day-card${isOver ? ' drag-over' : ''}`} data-day-id={day.id} id={`itinerary-day-${day.id}`} ref={setNodeRef}>
      <div className="day-heading itinerary-day-heading">
        <div className="day-heading-main"><span className="day-node"><span>Day</span>{day.dayIndex}</span><div><p className="day-kicker">第 {day.dayIndex} 天</p><h2>{formatDayDate(day.date)}</h2>{day.title ? <p className="day-title-note">{day.title}</p> : null}</div></div>
        <div className="day-heading-meta">{weather ? <WeatherBadge weather={weather} /> : <span className="weather-placeholder"><CalendarDays size={15} />天气待定</span>}<span className="day-place-count">{day.places.length} 个地点</span></div>
      </div>
      {canEdit ? <form className="compact-form day-meta-form" onSubmit={handleSubmit}><input maxLength={80} onChange={(event) => setTitle(event.target.value)} placeholder="当天标题，例如：慢慢走进老城" value={title} /><textarea maxLength={500} onChange={(event) => setSummary(event.target.value)} placeholder="给这一天留下一句摘要" rows={2} value={summary} /><button className="secondary-button" type="submit"><FormInput size={15} />保存当天信息</button></form> : day.summary ? <p className="day-summary-readonly">{day.summary}</p> : null}
      <TripPlaceList canEdit={canEdit} dayId={day.id} onDelete={onDeletePlace} onSelectPlace={onSelectPlace} onToggleCompleted={onTogglePlaceCompleted} onUpdate={onUpdatePlace} places={day.places} selectedPlaceId={selectedPlaceId} />
      {canEdit ? <div className="day-add-place-area">{isFormOpen ? <TripPlaceForm isSubmitting={isCreatingPlace} onCancel={() => toggleForm(false)} onSubmit={async (input) => { await onCreatePlace(input); toggleForm(false); }} searchCity={searchCity} tripDayId={day.id} /> : <button className="add-place-button" onClick={() => toggleForm(true)} type="button"><Plus size={17} />添加地点</button>}</div> : null}
    </section>
  );
}

function formatDayDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(value)); }
