import { Link } from 'react-router-dom';
import { DashboardJournal } from '../types/dashboard.types';

type Props = {
  journals: DashboardJournal[];
};

export function RecentJournalList({ journals }: Props) {
  return (
    <section className="content-panel dashboard-card">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">记录</p>
          <h2>最近游记</h2>
        </div>
      </div>
      {journals.length === 0 ? (
        <p className="empty-state">还没有已发布的游记</p>
      ) : (
        <div className="dashboard-list journal-dashboard-list">
          {journals.map((journal) => (
            <Link
              className="dashboard-list-row"
              key={journal.id}
              to={`/trips/${journal.tripId}/journals/${journal.id}`}
            >
              <div>
                <strong>{journal.title}</strong>
                <span>{journal.tripTitle}</span>
                <p>{journal.summary || '暂无正文'}</p>
              </div>
              <time>{new Date(journal.createdAt).toLocaleDateString('zh-CN')}</time>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}
