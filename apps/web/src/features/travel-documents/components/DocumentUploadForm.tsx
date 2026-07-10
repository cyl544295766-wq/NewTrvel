import { FormEvent, useState } from 'react';
import { TripDay } from '../../itinerary/types/itinerary.types';
import {
  TravelDocumentInput,
  TravelDocumentType,
  travelDocumentTypeLabels,
  travelDocumentTypes,
} from '../types/travel-document.types';

const maxDocumentBytes = 10 * 1024 * 1024;
const allowedTypes = ['application/pdf', 'image/jpeg', 'image/png', 'image/webp'];

type Props = {
  days: TripDay[];
  isSubmitting: boolean;
  onSubmit: (input: TravelDocumentInput) => Promise<void>;
};

export function DocumentUploadForm({ days, isSubmitting, onSubmit }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [type, setType] = useState<TravelDocumentType>('other');
  const [title, setTitle] = useState('');
  const [notes, setNotes] = useState('');
  const [expiredAt, setExpiredAt] = useState('');
  const [tripDayId, setTripDayId] = useState('');
  const [tripPlaceId, setTripPlaceId] = useState('');
  const [isReminder, setIsReminder] = useState(false);
  const [error, setError] = useState('');
  const selectedDay = days.find((day) => day.id === tripDayId);
  const places = selectedDay?.places ?? [];

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    if (!file) {
      setError('请选择要上传的文档');
      return;
    }
    if (!title.trim()) {
      setError('请填写文档标题');
      return;
    }
    if (!allowedTypes.includes(file.type)) {
      setError('仅支持 PDF、jpg、png、webp 文件');
      return;
    }
    if (file.size > maxDocumentBytes) {
      setError('单个文档不能超过 10MB');
      return;
    }

    await onSubmit({
      type,
      title: title.trim(),
      url: await readFileAsDataUrl(file),
      notes: notes.trim() || undefined,
      expiredAt: expiredAt ? new Date(`${expiredAt}T00:00:00`).toISOString() : undefined,
      tripDayId: tripDayId || undefined,
      tripPlaceId: tripPlaceId || undefined,
      isReminder,
    });

    setFile(null);
    setType('other');
    setTitle('');
    setNotes('');
    setExpiredAt('');
    setTripDayId('');
    setTripPlaceId('');
    setIsReminder(false);
  }

  return (
    <form className="trip-form document-upload-form" onSubmit={handleSubmit}>
      <div className="form-grid">
        <label>
          <span>选择文件</span>
          <input
            accept="application/pdf,image/jpeg,image/png,image/webp"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            type="file"
          />
        </label>
        <label>
          <span>文档类型</span>
          <select
            value={type}
            onChange={(event) => setType(event.target.value as TravelDocumentType)}
          >
            {travelDocumentTypes.map((documentType) => (
              <option key={documentType} value={documentType}>
                {travelDocumentTypeLabels[documentType]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label>
        <span>标题</span>
        <input
          maxLength={100}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="例如：日本电子签证"
          value={title}
        />
      </label>
      <label>
        <span>备注</span>
        <textarea
          maxLength={1000}
          onChange={(event) => setNotes(event.target.value)}
          placeholder="可填写使用说明、订单号或注意事项"
          value={notes}
        />
      </label>
      <div className="form-grid">
        <label>
          <span>过期日期</span>
          <input
            onChange={(event) => setExpiredAt(event.target.value)}
            type="date"
            value={expiredAt}
          />
        </label>
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
      </div>
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
      <label className="inline-checkbox">
        <input
          checked={isReminder}
          onChange={(event) => setIsReminder(event.target.checked)}
          type="checkbox"
        />
        到期前在仪表盘提醒
      </label>
      {error ? <p className="form-error">{error}</p> : null}
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '上传中...' : '上传文档'}
      </button>
    </form>
  );
}

function readFileAsDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => resolve(String(reader.result)));
    reader.addEventListener('error', () => reject(new Error('文档读取失败')));
    reader.readAsDataURL(file);
  });
}
