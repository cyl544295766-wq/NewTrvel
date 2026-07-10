import { useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useTripDays } from '../../itinerary/hooks/useItinerary';
import { PhotoDetailModal } from '../components/PhotoDetailModal';
import { PhotoGrid } from '../components/PhotoGrid';
import { PhotoUploadForm } from '../components/PhotoUploadForm';
import { useCreatePhoto, useDeletePhoto, usePhotos, useUpdatePhoto } from '../hooks/usePhotos';
import { Photo, PhotoInput, PhotoUpdateInput } from '../types/photo.types';

export function PhotosPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const days = useTripDays(safeTripId);
  const photos = usePhotos(safeTripId);
  const createPhoto = useCreatePhoto(safeTripId);
  const updatePhoto = useUpdatePhoto(safeTripId);
  const deletePhoto = useDeletePhoto(safeTripId);
  const [showUploadForm, setShowUploadForm] = useState(false);
  const [selectedPhoto, setSelectedPhoto] = useState<Photo | null>(null);

  if (!tripId) return <Navigate replace to="/" />;

  async function handleUpload(inputs: PhotoInput[]) {
    for (const input of inputs) {
      await createPhoto.mutateAsync(input);
    }
    setShowUploadForm(false);
  }

  async function handleSave(photoId: string, input: PhotoUpdateInput) {
    const result = await updatePhoto.mutateAsync({ photoId, input });
    setSelectedPhoto(result.photo);
  }

  async function handleDelete(photoId: string) {
    await deletePhoto.mutateAsync(photoId);
    setSelectedPhoto(null);
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">照片</p>
            <h1>照片中心</h1>
          </div>
          <button onClick={() => setShowUploadForm((current) => !current)} type="button">
            上传照片
          </button>
        </div>
        {showUploadForm ? (
          <PhotoUploadForm
            days={days.data?.days ?? []}
            isSubmitting={createPhoto.isPending}
            onSubmit={handleUpload}
          />
        ) : null}
        {photos.isLoading ? <p>加载中...</p> : null}
        {photos.isError ? <p className="form-error">照片加载失败</p> : null}
        {photos.data ? (
          <PhotoGrid photos={photos.data.photos} onSelect={(photo) => setSelectedPhoto(photo)} />
        ) : null}
      </section>
      {selectedPhoto ? (
        <PhotoDetailModal
          days={days.data?.days ?? []}
          isDeleting={deletePhoto.isPending}
          isSaving={updatePhoto.isPending}
          onClose={() => setSelectedPhoto(null)}
          onDelete={handleDelete}
          onSave={handleSave}
          photo={selectedPhoto}
        />
      ) : null}
    </main>
  );
}
