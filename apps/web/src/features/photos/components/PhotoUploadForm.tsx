import { FormEvent, useState } from 'react';
import { TripDay } from '../../itinerary/types/itinerary.types';
import { PhotoInput } from '../types/photo.types';

const maxPhotoBytes = 5 * 1024 * 1024;
const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];

type Props = {
  days: TripDay[];
  isSubmitting: boolean;
  onSubmit: (photos: PhotoInput[]) => Promise<void>;
};

export function PhotoUploadForm({ days, isSubmitting, onSubmit }: Props) {
  const [files, setFiles] = useState<FileList | null>(null);
  const [caption, setCaption] = useState('');
  const [shotAt, setShotAt] = useState('');
  const [tripDayId, setTripDayId] = useState('');
  const [tripPlaceId, setTripPlaceId] = useState('');
  const [coverIndex, setCoverIndex] = useState('0');
  const [error, setError] = useState('');
  const selectedDay = days.find((day) => day.id === tripDayId);
  const places = selectedDay?.places ?? [];

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    if (!files || files.length === 0) {
      setError('请选择要上传的照片');
      return;
    }

    const fileList = Array.from(files);
    const invalidFile = fileList.find(
      (file) => !allowedTypes.includes(file.type) || file.size > maxPhotoBytes,
    );
    if (invalidFile) {
      setError('仅支持 jpg、png、webp，单张图片不能超过 5MB');
      return;
    }

    const uploads = await Promise.all(
      fileList.map(async (file, index) => ({
        url: await readFileAsDataUrl(file),
        caption: caption || undefined,
        shotAt: shotAt ? new Date(shotAt).toISOString() : undefined,
        tripDayId: tripDayId || undefined,
        tripPlaceId: tripPlaceId || undefined,
        isCover: String(index) === coverIndex,
      })),
    );

    await onSubmit(uploads);
    setFiles(null);
    setCaption('');
    setShotAt('');
    setTripDayId('');
    setTripPlaceId('');
    setCoverIndex('0');
  }

  return (
    <form className="trip-form photo-upload-form" onSubmit={handleSubmit}>
      <label>
        <span>选择照片</span>
        <input
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(event) => setFiles(event.target.files)}
          type="file"
        />
      </label>
      <div className="form-grid">
        <label>
          <span>备注</span>
          <input
            maxLength={300}
            onChange={(event) => setCaption(event.target.value)}
            placeholder="可填写照片说明"
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
        <span>设为封面</span>
        <select value={coverIndex} onChange={(event) => setCoverIndex(event.target.value)}>
          {Array.from({ length: files?.length ?? 1 }, (_, index) => (
            <option key={index} value={String(index)}>
              第 {index + 1} 张
            </option>
          ))}
        </select>
      </label>
      {error ? <p className="form-error">{error}</p> : null}
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '上传中...' : '上传照片'}
      </button>
    </form>
  );
}

function readFileAsDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => resolve(String(reader.result)));
    reader.addEventListener('error', () => reject(new Error('照片读取失败')));
    reader.readAsDataURL(file);
  });
}
