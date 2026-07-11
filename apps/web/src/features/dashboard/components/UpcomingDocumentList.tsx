import { Link } from 'react-router-dom';
import { travelDocumentTypeLabels } from '../../travel-documents/types/travel-document.types';
import { DashboardDocument } from '../types/dashboard.types';

type Props = {
  documents: DashboardDocument[];
};

export function UpcomingDocumentList({ documents }: Props) {
  return (
    <section className="content-panel dashboard-card dashboard-card-documents">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">提醒</p>
          <h2>即将过期文档</h2>
        </div>
      </div>
      {documents.length === 0 ? (
        <p className="empty-state">未来 30 天没有即将过期的文档</p>
      ) : (
        <div className="dashboard-list">
          {documents.map((document) => (
            <Link
              className="dashboard-list-row"
              key={document.id}
              to={`/trips/${document.tripId}/documents`}
            >
              <div>
                <strong>{document.title}</strong>
                <span>
                  {travelDocumentTypeLabels[document.type]} · {document.tripTitle}
                </span>
              </div>
              <time>{new Date(document.expiredAt).toLocaleDateString('zh-CN')}</time>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}
