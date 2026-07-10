import { FormEvent, useState } from 'react';
import { Trip, TripStatus, TripUpdateInput } from '../types/trip.types';

type TripFormProps = {
  trip?: Trip;
  includeStatus?: boolean;
  isSubmitting: boolean;
  onSubmit: (input: TripUpdateInput) => Promise<void>;
};

const statuses: TripStatus[] = ['draft', 'planning', 'active', 'completed', 'archived'];

export function TripForm({ trip, includeStatus = false, isSubmitting, onSubmit }: TripFormProps) {
  const [title, setTitle] = useState(trip?.title ?? '');
  const [description, setDescription] = useState(trip?.description ?? '');
  const [destination, setDestination] = useState(trip?.destination ?? '');
  const [startDate, setStartDate] = useState(toDateInput(trip?.startDate));
  const [endDate, setEndDate] = useState(toDateInput(trip?.endDate));
  const [status, setStatus] = useState<TripStatus>(trip?.status ?? 'draft');
  const [coverImageUrl, setCoverImageUrl] = useState(trip?.coverImageUrl ?? '');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    await onSubmit({
      title,
      description: description || undefined,
      destination: destination || undefined,
      startDate: startDate || undefined,
      endDate: endDate || undefined,
      status: includeStatus ? status : undefined,
      coverImageUrl: coverImageUrl || undefined,
    });
  }

  return (
    <form className="trip-form" onSubmit={handleSubmit}>
      <label>
        <span>标题</span>
        <input
          maxLength={80}
          onChange={(event) => setTitle(event.target.value)}
          required
          value={title}
        />
      </label>
      <label>
        <span>目的地</span>
        <input
          maxLength={120}
          onChange={(event) => setDestination(event.target.value)}
          value={destination}
        />
      </label>
      <div className="form-grid">
        <label>
          <span>开始日期</span>
          <input
            onChange={(event) => setStartDate(event.target.value)}
            type="date"
            value={startDate}
          />
        </label>
        <label>
          <span>结束日期</span>
          <input
            min={startDate}
            onChange={(event) => setEndDate(event.target.value)}
            type="date"
            value={endDate}
          />
        </label>
      </div>
      {includeStatus ? (
        <label>
          <span>状态</span>
          <select onChange={(event) => setStatus(event.target.value as TripStatus)} value={status}>
            {statuses.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      <label>
        <span>封面图地址</span>
        <input
          onChange={(event) => setCoverImageUrl(event.target.value)}
          type="url"
          value={coverImageUrl}
        />
      </label>
      <label>
        <span>描述</span>
        <textarea
          maxLength={1000}
          onChange={(event) => setDescription(event.target.value)}
          rows={5}
          value={description}
        />
      </label>
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '保存中...' : '保存旅行'}
      </button>
    </form>
  );
}

function toDateInput(value?: string | null) {
  return value ? value.slice(0, 10) : '';
}
