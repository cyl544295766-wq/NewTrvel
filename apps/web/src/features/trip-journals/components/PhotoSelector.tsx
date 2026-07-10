import { Photo } from '../../photos/types/photo.types';

type Props = {
  onChange: (photoIds: string[]) => void;
  photos: Photo[];
  selectedPhotoIds: string[];
};

export function PhotoSelector({ onChange, photos, selectedPhotoIds }: Props) {
  const photoById = new Map(photos.map((photo) => [photo.id, photo]));
  const selectedPhotos = selectedPhotoIds
    .map((photoId) => photoById.get(photoId))
    .filter((photo): photo is Photo => Boolean(photo));

  function togglePhoto(photoId: string, selected: boolean) {
    onChange(
      selected
        ? [...selectedPhotoIds, photoId]
        : selectedPhotoIds.filter((selectedId) => selectedId !== photoId),
    );
  }

  function movePhoto(index: number, direction: -1 | 1) {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= selectedPhotoIds.length) return;
    const next = [...selectedPhotoIds];
    [next[index], next[targetIndex]] = [next[targetIndex], next[index]];
    onChange(next);
  }

  return (
    <fieldset className="journal-photo-selector">
      <legend>关联照片</legend>
      {photos.length === 0 ? (
        <p className="empty-state">当前旅行还没有可关联的照片</p>
      ) : (
        <>
          {selectedPhotos.length > 0 ? (
            <div className="journal-selected-photos" aria-label="已选照片顺序">
              {selectedPhotos.map((photo, index) => (
                <article key={photo.id}>
                  <img alt={photo.caption || '旅行照片'} src={photo.url} />
                  <span>第 {index + 1} 张</span>
                  <div>
                    <button
                      className="secondary-button"
                      disabled={index === 0}
                      onClick={() => movePhoto(index, -1)}
                      type="button"
                    >
                      前移
                    </button>
                    <button
                      className="secondary-button"
                      disabled={index === selectedPhotos.length - 1}
                      onClick={() => movePhoto(index, 1)}
                      type="button"
                    >
                      后移
                    </button>
                  </div>
                </article>
              ))}
            </div>
          ) : null}
          <div className="journal-photo-options">
            {photos.map((photo) => (
              <label key={photo.id}>
                <input
                  checked={selectedPhotoIds.includes(photo.id)}
                  onChange={(event) => togglePhoto(photo.id, event.target.checked)}
                  type="checkbox"
                />
                <img alt={photo.caption || '旅行照片'} src={photo.url} />
                <span>{photo.caption || '未命名照片'}</span>
              </label>
            ))}
          </div>
        </>
      )}
    </fieldset>
  );
}
