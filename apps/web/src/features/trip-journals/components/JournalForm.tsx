import { FormEvent, useState } from 'react';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { Photo } from '../../photos/types/photo.types';
import {
  JournalMood,
  TripJournal,
  journalMoodLabels,
  journalMoods,
} from '../types/trip-journal.types';
import { PhotoSelector } from './PhotoSelector';

export type JournalFormValues = {
  title: string;
  content: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  mood: JournalMood | null;
  isDraft: boolean;
  photoIds: string[];
};

type Props = {
  days: TripDay[];
  initialJournal?: TripJournal;
  isSubmitting: boolean;
  onCancel: () => void;
  onSubmit: (input: JournalFormValues) => Promise<void>;
  photos: Photo[];
};

export function JournalForm({
  days,
  initialJournal,
  isSubmitting,
  onCancel,
  onSubmit,
  photos,
}: Props) {
  const [title, setTitle] = useState(initialJournal?.title ?? '');
  const [content, setContent] = useState(initialJournal?.content ?? '');
  const [tripDayId, setTripDayId] = useState(initialJournal?.tripDayId ?? '');
  const [tripPlaceId, setTripPlaceId] = useState(initialJournal?.tripPlaceId ?? '');
  const [mood, setMood] = useState<JournalMood | ''>(initialJournal?.mood ?? '');
  const [isDraft, setIsDraft] = useState(initialJournal?.isDraft ?? true);
  const [photoIds, setPhotoIds] = useState(initialJournal?.photos.map((photo) => photo.id) ?? []);
  const [error, setError] = useState('');
  const selectedDay = days.find((day) => day.id === tripDayId);
  const places = selectedDay?.places ?? [];

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedTitle = title.trim();
    if (!normalizedTitle) {
      setError('请填写游记标题');
      return;
    }
    await onSubmit({
      title: normalizedTitle,
      content,
      tripDayId: tripDayId || null,
      tripPlaceId: tripPlaceId || null,
      mood: mood || null,
      isDraft,
      photoIds,
    });
  }

  return (
    <form className="trip-form journal-form" onSubmit={handleSubmit}>
      <div className="form-grid">
        <label>
          <span>标题</span>
          <input
            maxLength={200}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="记录今天最难忘的事情"
            value={title}
          />
        </label>
        <label>
          <span>心情</span>
          <select
            value={mood}
            onChange={(event) => setMood(event.target.value as JournalMood | '')}
          >
            <option value="">未设置</option>
            {journalMoods.map((item) => (
              <option key={item} value={item}>
                {journalMoodLabels[item]}
              </option>
            ))}
          </select>
        </label>
      </div>
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
      <label>
        <span>正文</span>
        <textarea
          className="journal-markdown-input"
          maxLength={20000}
          onChange={(event) => setContent(event.target.value)}
          placeholder="支持 Markdown 纯文本，例如：## 今日见闻"
          value={content}
        />
      </label>
      <label>
        <span>发布状态</span>
        <select
          value={isDraft ? 'draft' : 'published'}
          onChange={(event) => setIsDraft(event.target.value === 'draft')}
        >
          <option value="draft">草稿</option>
          <option value="published">已发布</option>
        </select>
      </label>
      <PhotoSelector onChange={setPhotoIds} photos={photos} selectedPhotoIds={photoIds} />
      {error ? <p className="form-error">{error}</p> : null}
      <div className="form-actions">
        <button disabled={isSubmitting} type="submit">
          {isSubmitting ? '保存中...' : initialJournal ? '保存游记' : '创建游记'}
        </button>
        <button className="secondary-button" onClick={onCancel} type="button">
          取消
        </button>
      </div>
    </form>
  );
}
