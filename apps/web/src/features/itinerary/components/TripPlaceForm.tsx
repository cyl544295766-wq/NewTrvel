import { FormEvent, useState } from 'react';
import { typeLabels } from './PlaceTypeIcon';
import { TripPlaceInput, TripPlaceType } from '../types/itinerary.types';

const placeTypes: TripPlaceType[] = [
  'attraction',
  'hotel',
  'restaurant',
  'transport',
  'shopping',
  'custom',
];

type Props = {
  tripDayId: string;
  isSubmitting: boolean;
  onSubmit: (input: TripPlaceInput) => Promise<void>;
};

export function TripPlaceForm({ tripDayId, isSubmitting, onSubmit }: Props) {
  const [name, setName] = useState('');
  const [type, setType] = useState<TripPlaceType>('custom');
  const [address, setAddress] = useState('');
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [notes, setNotes] = useState('');
  const [isCompleted, setIsCompleted] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSubmit({
      tripDayId,
      name,
      type,
      address: address || undefined,
      startTime: startTime || undefined,
      endTime: endTime || undefined,
      notes: notes || undefined,
      isCompleted,
    });
    setName('');
    setType('custom');
    setAddress('');
    setStartTime('');
    setEndTime('');
    setNotes('');
    setIsCompleted(false);
  }

  return (
    <form className="compact-form" onSubmit={handleSubmit}>
      <label>
        <span>地点名称</span>
        <input
          maxLength={120}
          onChange={(event) => setName(event.target.value)}
          placeholder="地点名称"
          required
          value={name}
        />
      </label>
      <label>
        <span>类型</span>
        <select onChange={(event) => setType(event.target.value as TripPlaceType)} value={type}>
          {placeTypes.map((item) => (
            <option key={item} value={item}>
              {typeLabels[item].label}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>地址</span>
        <input
          maxLength={300}
          onChange={(event) => setAddress(event.target.value)}
          placeholder="地址"
          value={address}
        />
      </label>
      <div className="form-grid">
        <label>
          <span>开始时间</span>
          <input
            onChange={(event) => setStartTime(event.target.value)}
            type="datetime-local"
            value={startTime}
          />
        </label>
        <label>
          <span>结束时间</span>
          <input
            onChange={(event) => setEndTime(event.target.value)}
            type="datetime-local"
            value={endTime}
          />
        </label>
      </div>
      <label className="inline-checkbox">
        <input
          checked={isCompleted}
          onChange={(event) => setIsCompleted(event.target.checked)}
          type="checkbox"
        />
        已完成
      </label>
      <label>
        <span>备注</span>
        <textarea
          maxLength={1000}
          onChange={(event) => setNotes(event.target.value)}
          placeholder="备注"
          rows={3}
          value={notes}
        />
      </label>
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '保存中...' : '添加地点'}
      </button>
    </form>
  );
}
