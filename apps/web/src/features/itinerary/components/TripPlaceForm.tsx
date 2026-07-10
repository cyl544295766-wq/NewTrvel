import { FormEvent, useState } from 'react';
import { TripPlaceInput, TripPlaceType } from '../types/itinerary.types';

const placeTypes: { value: TripPlaceType; label: string }[] = [
  { value: 'attraction', label: '景点' },
  { value: 'hotel', label: '酒店' },
  { value: 'restaurant', label: '餐厅' },
  { value: 'transport', label: '交通' },
  { value: 'shopping', label: '购物' },
  { value: 'custom', label: '自定义' },
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
    });
    setName('');
    setAddress('');
    setStartTime('');
    setEndTime('');
    setNotes('');
  }

  return (
    <form className="compact-form" onSubmit={handleSubmit}>
      <input
        maxLength={120}
        onChange={(event) => setName(event.target.value)}
        placeholder="地点名称"
        required
        value={name}
      />
      <select onChange={(event) => setType(event.target.value as TripPlaceType)} value={type}>
        {placeTypes.map((item) => (
          <option key={item.value} value={item.value}>
            {item.label}
          </option>
        ))}
      </select>
      <input
        maxLength={300}
        onChange={(event) => setAddress(event.target.value)}
        placeholder="地址"
        value={address}
      />
      <div className="form-grid">
        <input
          onChange={(event) => setStartTime(event.target.value)}
          type="datetime-local"
          value={startTime}
        />
        <input
          onChange={(event) => setEndTime(event.target.value)}
          type="datetime-local"
          value={endTime}
        />
      </div>
      <textarea
        maxLength={1000}
        onChange={(event) => setNotes(event.target.value)}
        placeholder="备注"
        rows={3}
        value={notes}
      />
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '保存中...' : '添加地点'}
      </button>
    </form>
  );
}
