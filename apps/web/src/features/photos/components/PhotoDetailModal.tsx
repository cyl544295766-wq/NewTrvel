import { FormEvent, useEffect, useState } from 'react';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { Photo, PhotoUpdateInput } from '../types/photo.types';

type Props = {
  days: TripDay[];
  isDeleting: boolean;
  isSaving: boolean;
  onClose: () => void;
  onDelete: (photoId: string) => void;
  onSave: (photoId: string, input: PhotoUpdateInput) => Promise<void>;
  photo: Photo;
};

export function PhotoDetailModal({
  days,
  isDeleting,
  isSaving,
  onClose,
  onDelete,
  onSave,
  photo,
}: Props) {
  const [caption, setCaption] = useState(photo.caption ?? '');
  const [shotAt, setShotAt] = useState(toDateTimeInput(photo.shotAt));
  const [tripDayId, setTripDayId] = useState(photo.tripDayId ?? '');
  const [tripPlaceId, setTripPlaceId] = useState(photo.tripPlaceId ?? '');
  const [isCover, setIsCover] = useState(photo.isCover);
  const selectedDay = days.find((day) => day.id === tripDayId);
  const places = selectedDay?.places ?? [];

  useEffect(() => {
    setCaption(photo.caption ?? '');
    setShotAt(toDateTimeInput(photo.shotAt));
    setTripDayId(photo.tripDayId ?? '');
    setTripPlaceId(photo.tripPlaceId ?? '');
    setIsCover(photo.isCover);
  }, [photo]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSave(photo.id, {
      caption,
      shotAt: shotAt ? new Date(shotAt).toISOString() : null,
      tripDayId: tripDayId || null,
      tripPlaceId: tripPlaceId || null,
      isCover,
    });
  }

  return (
    <div className="dialog-backdrop">
      <section className="photo-detail-modal">
        <div className="photo-detail-image">
          <img alt={photo.caption || '旅行照片'} src={photo.url} />
        </div>
        <form className="trip-form photo-detail-form" onSubmit={handleSubmit}>
          <div className="panel-heading">
            <div>
              <p className="eyebrow">照片</p>
              <h2>照片详情</h2>
            </div>
            <button className="secondary-button" onClick={onClose} type="button">
              取消
            </button>
          </div>
          <label>
            <span>备注</span>
            <input
              maxLength={300}
              onChange={(event) => setCaption(event.target.value)}
              value={caption}
            />
          </label>
          <label>
            <span>拍摄时间</span>
            <input
              onChange={(event) => setShotAt(event.target.value)}
              type="datetime-local"
              value={shotAt}
            />
          </label>
          <div className="form-grid">
            <label>
              <span>关联日期</span>
              <select
                value={tripDayId}
                onChange={(event) => {
                  setTripDayId(event.target.value);
                  setTripPlaceId('');
                }}
              >
                <option value="">不关联日期</option>
                {days.map((day) => (
                  <option key={day.id} value={day.id}>
                    第 {day.dayIndex} 天
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>关联地点</span>
              <select value={tripPlaceId} onChange={(event) => setTripPlaceId(event.target.value)}>
                <option value="">不关联地点</option>
                {places.map((place) => (
                  <option key={place.id} value={place.id}>
                    {place.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="inline-checkbox">
            <input
              checked={isCover}
              onChange={(event) => setIsCover(event.target.checked)}
              type="checkbox"
            />
            设为封面
          </label>
          <div className="form-actions">
            <button disabled={isSaving} type="submit">
              {isSaving ? '保存中...' : '保存'}
            </button>
            <button
              className="secondary-button danger-button"
              disabled={isDeleting}
              onClick={() => onDelete(photo.id)}
              type="button"
            >
              删除照片
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function toDateTimeInput(value: string | null) {
  return value ? new Date(value).toISOString().slice(0, 16) : '';
}
