import { FileText } from 'lucide-react';
import {
  TravelDocument,
  travelDocumentTypeLabels,
  travelDocumentTypes,
} from '../types/travel-document.types';

type Props = {
  documents: TravelDocument[];
  onSelect: (document: TravelDocument) => void;
};

export function DocumentList({ documents, onSelect }: Props) {
  if (documents.length === 0) {
    return <p className="empty-state">还没有旅行文档，上传第一份证件或订单吧</p>;
  }

  return (
    <div className="document-groups">
      {travelDocumentTypes.map((type) => {
        const groupedDocuments = documents.filter((document) => document.type === type);
        if (groupedDocuments.length === 0) return null;

        return (
          <section className="document-group" key={type}>
            <h2>{travelDocumentTypeLabels[type]}</h2>
            <div className="document-list">
              {groupedDocuments.map((document) => (
                <button
                  className="document-row"
                  key={document.id}
                  onClick={() => onSelect(document)}
                  type="button"
                >
                  <DocumentThumbnail document={document} />
                  <span className="document-row-copy">
                    <strong>{document.title}</strong>
                    <span>{formatDocumentMeta(document)}</span>
                  </span>
                  {document.isReminder ? <span className="document-reminder">提醒</span> : null}
                </button>
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function DocumentThumbnail({ document }: { document: TravelDocument }) {
  if (document.url.startsWith('data:image/')) {
    return <img className="document-thumbnail" alt="" src={document.url} />;
  }

  return (
    <span className="document-thumbnail document-thumbnail-pdf" aria-hidden="true">
      <FileText size={24} strokeWidth={1.8} />
    </span>
  );
}

function formatDocumentMeta(document: TravelDocument) {
  const relation =
    document.tripPlace?.name ??
    (document.tripDay ? `第 ${document.tripDay.dayIndex} 天` : '未关联行程');
  const expiration = document.expiredAt
    ? `有效期至 ${new Date(document.expiredAt).toLocaleDateString('zh-CN')}`
    : '未设置过期时间';
  return `${expiration} · ${relation}`;
}
