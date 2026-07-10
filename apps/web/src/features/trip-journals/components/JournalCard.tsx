import { TripJournal, journalMoodLabels } from '../types/trip-journal.types';

type Props = {
  compact?: boolean;
  journal: TripJournal;
};

export function JournalCard({ compact = false, journal }: Props) {
  const relation =
    journal.tripPlace?.name ??
    (journal.tripDay ? `第 ${journal.tripDay.dayIndex} 天` : '未关联行程日');
  const content =
    compact && journal.content.length > 180
      ? `${journal.content.slice(0, 180)}...`
      : journal.content;

  return (
    <article className={compact ? 'journal-card compact' : 'journal-card'}>
      <header>
        <div>
          <p className="eyebrow">{journal.isDraft ? '草稿' : '已发布'}</p>
          <h2>{journal.title}</h2>
        </div>
        {journal.mood ? <span>{journalMoodLabels[journal.mood]}</span> : null}
      </header>
      <p className="journal-meta">
        {new Date(journal.createdAt).toLocaleDateString('zh-CN')} · {relation}
      </p>
      <div className="journal-content">{content || '暂无正文'}</div>
      {journal.photos.length > 0 ? (
        <div className="journal-photo-strip">
          {journal.photos.map((photo) => (
            <img key={photo.id} alt={photo.caption || journal.title} src={photo.url} />
          ))}
        </div>
      ) : null}
    </article>
  );
}
