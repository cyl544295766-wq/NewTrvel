import { FormEvent, useState } from 'react';
import { PlaceAutocomplete } from '../../places/components/PlaceAutocomplete';
import { PlaceSuggestion } from '../../places/types/place.types';
import { typeLabels } from './PlaceTypeIcon';
import { TripPlaceInput, TripPlaceType } from '../types/itinerary.types';

const placeTypes: TripPlaceType[] = ['attraction', 'hotel', 'restaurant', 'transport', 'shopping', 'custom'];

type Props = {
  tripDayId: string;
  searchCity?: string;
  isSubmitting: boolean;
  onSubmit: (input: TripPlaceInput) => Promise<void>;
};

export function TripPlaceForm({ tripDayId, searchCity, isSubmitting, onSubmit }: Props) {
  const [name, setName] = useState('');
  const [type, setType] = useState<TripPlaceType>('custom');
  const [address, setAddress] = useState('');
  const [latitude, setLatitude] = useState<number | undefined>();
  const [longitude, setLongitude] = useState<number | undefined>();
  const [selectedSuggestion, setSelectedSuggestion] = useState<PlaceSuggestion | null>(null);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [notes, setNotes] = useState('');
  const [isCompleted, setIsCompleted] = useState(false);

  function selectSuggestion(suggestion: PlaceSuggestion) {
    setSelectedSuggestion(suggestion);
    setName(suggestion.name);
    setType(suggestion.type);
    setAddress(suggestion.address || suggestion.district || '');
    setLatitude(suggestion.latitude ? Number(suggestion.latitude) : undefined);
    setLongitude(suggestion.longitude ? Number(suggestion.longitude) : undefined);
  }

  function clearSelection() {
    setSelectedSuggestion(null);
    setLatitude(undefined);
    setLongitude(undefined);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSubmit({ tripDayId, name: name.trim(), type, address: address.trim() || undefined, latitude, longitude, startTime: startTime || undefined, endTime: endTime || undefined, notes: notes.trim() || undefined, isCompleted });
    setName(''); setType('custom'); setAddress(''); setLatitude(undefined); setLongitude(undefined); setSelectedSuggestion(null); setStartTime(''); setEndTime(''); setNotes(''); setIsCompleted(false);
  }

  return (
    <form className="compact-form trip-place-form" onSubmit={handleSubmit}>
      <PlaceAutocomplete city={searchCity} onChange={setName} onClearSelection={clearSelection} onSelect={selectSuggestion} selectedSuggestion={selectedSuggestion} value={name} />
      <div className="form-grid">
        <label><span>类型</span><select onChange={(event) => setType(event.target.value as TripPlaceType)} value={type}>{placeTypes.map((item) => <option key={item} value={item}>{typeLabels[item].label}</option>)}</select></label>
        <label><span>地址</span><input maxLength={300} onChange={(event) => setAddress(event.target.value)} placeholder="选择地点后自动填写，也可手动补充" value={address} /></label>
      </div>
      {!selectedSuggestion && name.trim() ? <p className="custom-place-note">将作为自定义地点保存；没有地图坐标的地点不会显示在地图中。</p> : null}
      <div className="form-grid">
        <label><span>开始时间</span><input onChange={(event) => setStartTime(event.target.value)} type="datetime-local" value={startTime} /></label>
        <label><span>结束时间</span><input onChange={(event) => setEndTime(event.target.value)} type="datetime-local" value={endTime} /></label>
      </div>
      <label className="inline-checkbox"><input checked={isCompleted} onChange={(event) => setIsCompleted(event.target.checked)} type="checkbox" />已完成</label>
      <label><span>备注</span><textarea maxLength={1000} onChange={(event) => setNotes(event.target.value)} placeholder="记录预约、开放时间或旅行想法" rows={3} value={notes} /></label>
      <button disabled={isSubmitting || !name.trim()} type="submit">{isSubmitting ? '保存中...' : selectedSuggestion?.latitude ? '添加并在地图标记' : '添加地点'}</button>
    </form>
  );
}
