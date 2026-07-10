import { FormEvent, useEffect, useState } from 'react';
import { FileText } from 'lucide-react';
import { TripDay } from '../../itinerary/types/itinerary.types';
import {
  TravelDocument,
  TravelDocumentType,
  TravelDocumentUpdateInput,
  travelDocumentTypeLabels,
  travelDocumentTypes,
} from '../types/travel-document.types';

type Props = {
  days: TripDay[];
  document: TravelDocument;
  isDeleting: boolean;
  isSaving: boolean;
  onClose: () => void;
  onDelete: (documentId: string) => void;
  onSave: (documentId: string, input: TravelDocumentUpdateInput) => Promise<void>;
};

export function DocumentDetailModal({
  days,
  document,
  isDeleting,
  isSaving,
  onClose,
  onDelete,
  onSave,
}: Props) {
  const [type, setType] = useState(document.type);
  const [title, setTitle] = useState(document.title);
  const [notes, setNotes] = useState(document.notes ?? '');
  const [expiredAt, setExpiredAt] = useState(toDateInput(document.expiredAt));
  const [tripDayId, setTripDayId] = useState(document.tripDayId ?? '');
  const [tripPlaceId, setTripPlaceId] = useState(document.tripPlaceId ?? '');
  const [isReminder, setIsReminder] = useState(document.isReminder);
  const selectedDay = days.find((day) => day.id === tripDayId);
  const places = selectedDay?.places ?? [];

  useEffect(() => {
    setType(document.type);
    setTitle(document.title);
    setNotes(document.notes ?? '');
    setExpiredAt(toDateInput(document.expiredAt));
    setTripDayId(document.tripDayId ?? '');
    setTripPlaceId(document.tripPlaceId ?? '');
    setIsReminder(document.isReminder);
  }, [document]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onSave(document.id, {
      type,
      title: title.trim(),
      notes: notes.trim() || null,
      expiredAt: expiredAt ? new Date(`${expiredAt}T00:00:00`).toISOString() : null,
      tripDayId: tripDayId || null,
      tripPlaceId: tripPlaceId || null,
      isReminder,
    });
  }

  return (
    <div className="dialog-backdrop">
      <section className="document-detail-modal">
        <div className="document-preview">
          {document.url.startsWith('data:image/') ? (
            <img alt={document.title} src={document.url} />
          ) : (
            <div className="document-pdf-preview">
              <FileText size={54} strokeWidth={1.4} />
              <strong>PDF 文档</strong>
              <a download={`${document.title}.pdf`} href={document.url}>
                下载查看
              </a>
            </div>
          )}
        </div>
        <form className="trip-form document-detail-form" onSubmit={handleSubmit}>
          <div className="panel-heading">
            <div>
              <p className="eyebrow">旅行文档</p>
              <h2>文档详情</h2>
            </div>
            <button className="secondary-button" onClick={onClose} type="button">
              取消
            </button>
          </div>
          <div className="form-grid">
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
            <label>
              <span>标题</span>
              <input
                maxLength={100}
                onChange={(event) => setTitle(event.target.value)}
                required
                value={title}
              />
            </label>
          </div>
          <label>
            <span>备注</span>
            <textarea
              maxLength={1000}
              onChange={(event) => setNotes(event.target.value)}
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
          <div className="form-actions">
            <button disabled={isSaving || !title.trim()} type="submit">
              {isSaving ? '保存中...' : '保存'}
            </button>
            <button
              className="secondary-button danger-button"
              disabled={isDeleting}
              onClick={() => onDelete(document.id)}
              type="button"
            >
              删除文档
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function toDateInput(value: string | null) {
  return value ? new Date(value).toISOString().slice(0, 10) : '';
}
