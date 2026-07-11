import { Link } from 'react-router-dom';
import { DashboardPhoto } from '../types/dashboard.types';

type Props = {
  photos: DashboardPhoto[];
};

export function RecentPhotoList({ photos }: Props) {
  return (
    <section className="content-panel dashboard-card dashboard-card-photos">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">照片</p>
          <h2>最近照片</h2>
        </div>
      </div>
      {photos.length === 0 ? (
        <p className="empty-state">还没有照片，上传第一张旅行照片吧</p>
      ) : (
        <div className="photo-placeholder-grid">
          {photos.map((photo) => (
            <Link
              aria-label={`查看 ${photo.tripTitle} 的照片`}
              className="photo-placeholder"
              key={photo.id}
              to={`/trips/${photo.tripId}/photos`}
            >
              <img alt={photo.alt} src={photo.thumbnailUrl} />
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}
