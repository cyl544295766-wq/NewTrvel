import { Link } from 'react-router-dom';
import { ArrowUpRight } from 'lucide-react';
import { DashboardJournal, DashboardPhoto } from '../types/dashboard.types';

type Props = {
  journals: DashboardJournal[];
  photos?: DashboardPhoto[];
};

export function RecentJournalList({ journals, photos = [] }: Props) {
  const featuredJournal = journals[0];
  const featuredPhoto = photos.find((photo) => photo.tripId === featuredJournal?.tripId);

  return (
    <section className="content-panel dashboard-card dashboard-card-journals dashboard-featured-journal">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">记录</p>
          <h2>最近游记</h2>
        </div>
      </div>
      {!featuredJournal ? (
        <p className="empty-state">还没有已发布的游记</p>
      ) : (
        <Link
          className="dashboard-journal-feature"
          to={`/trips/${featuredJournal.tripId}/journals/${featuredJournal.id}`}
        >
          <div className="dashboard-journal-image">
            <img
              alt={`${featuredJournal.tripTitle}游记配图`}
              src={featuredPhoto?.thumbnailUrl ?? '/login-travel.webp'}
            />
          </div>
          <div className="dashboard-journal-copy">
            <span>{featuredJournal.tripTitle} · {new Date(featuredJournal.createdAt).toLocaleDateString('zh-CN')}</span>
            <h3>{featuredJournal.title}</h3>
            <p>{featuredJournal.summary || '暂无正文'}</p>
            <b>阅读游记 <ArrowUpRight size={15} /></b>
          </div>
        </Link>
      )}
    </section>
  );
}
