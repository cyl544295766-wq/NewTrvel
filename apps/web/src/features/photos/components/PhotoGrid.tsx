import { Photo } from '../types/photo.types';

type Props = {
  photos: Photo[];
  onSelect: (photo: Photo) => void;
};

export function PhotoGrid({ photos, onSelect }: Props) {
  if (photos.length === 0) {
    return <p className="empty-state">还没有照片，上传第一张旅行照片吧</p>;
  }

  const groups = groupPhotos(photos);

  return (
    <div className="photo-center-groups">
      {groups.map((group) => (
        <section className="photo-date-group" key={group.label}>
          <h2>{group.label}</h2>
          <div className="photo-grid">
            {group.photos.map((photo) => (
              <button
                className="photo-tile"
                key={photo.id}
                onClick={() => onSelect(photo)}
                type="button"
              >
                <img alt={photo.caption || '旅行照片'} src={photo.url} />
                {photo.isCover ? <span>封面</span> : null}
              </button>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function groupPhotos(photos: Photo[]) {
  const groupMap = new Map<string, Photo[]>();
  const sortedPhotos = [...photos].sort(
    (left, right) => getPhotoTimestamp(right) - getPhotoTimestamp(left),
  );

  for (const photo of sortedPhotos) {
    const label = photo.tripDay
      ? `第 ${photo.tripDay.dayIndex} 天`
      : formatDate(photo.shotAt ?? photo.createdAt);
    groupMap.set(label, [...(groupMap.get(label) ?? []), photo]);
  }

  return [...groupMap.entries()].map(([label, groupPhotos]) => ({ label, photos: groupPhotos }));
}

function getPhotoTimestamp(photo: Photo) {
  return new Date(photo.shotAt ?? photo.createdAt).getTime();
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}
