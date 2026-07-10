import { FormEvent, useState } from 'react';
import { TripDay, TripPlaceInput } from '../types/itinerary.types';
import { TripPlaceForm } from './TripPlaceForm';
import { TripPlaceList } from './TripPlaceList';

type Props = {
  day: TripDay;
  canEdit: boolean;
  onUpdateDay: (dayId: string, title: string, summary: string) => Promise<void>;
  onCreatePlace: (input: TripPlaceInput) => Promise<void>;
  onDeletePlace: (placeId: string) => void;
  isCreatingPlace: boolean;
};

export function TripDayCard({
  day,
  canEdit,
  onUpdateDay,
  onCreatePlace,
  onDeletePlace,
  isCreatingPlace,
}: Props) {
  const [title, setTitle] = useState(day.title ?? '');
  const [summary, setSummary] = useState(day.summary ?? '');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onUpdateDay(day.id, title, summary);
  }

  return (
    <section className="day-card">
      <div className="day-heading">
        <div>
          <p className="eyebrow">第 {day.dayIndex} 天</p>
          <h2>{new Date(day.date).toLocaleDateString('zh-CN')}</h2>
        </div>
      </div>
      {canEdit ? (
        <form className="compact-form" onSubmit={handleSubmit}>
          <input
            maxLength={80}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="当天标题"
            value={title}
          />
          <textarea
            maxLength={500}
            onChange={(event) => setSummary(event.target.value)}
            placeholder="当天摘要"
            rows={2}
            value={summary}
          />
          <button className="secondary-button" type="submit">
            保存当天信息
          </button>
        </form>
      ) : (
        <p>
          {day.title || '暂无当天标题'} {day.summary || ''}
        </p>
      )}
      <TripPlaceList canEdit={canEdit} onDelete={onDeletePlace} places={day.places} />
      {canEdit ? (
        <TripPlaceForm isSubmitting={isCreatingPlace} onSubmit={onCreatePlace} tripDayId={day.id} />
      ) : null}
    </section>
  );
}
