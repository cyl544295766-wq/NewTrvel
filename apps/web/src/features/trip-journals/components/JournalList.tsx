import { Link } from 'react-router-dom';
import { TripJournal } from '../types/trip-journal.types';
import { JournalCard } from './JournalCard';

type Props = {
  journals: TripJournal[];
  tripId: string;
};

export function JournalList({ journals, tripId }: Props) {
  if (journals.length === 0) {
    return <p className="empty-state">还没有游记，写下旅途中的第一段记录吧</p>;
  }

  return (
    <div className="journal-list">
      {journals.map((journal) => (
        <Link key={journal.id} to={`/trips/${tripId}/journals/${journal.id}`}>
          <JournalCard compact journal={journal} />
        </Link>
      ))}
    </div>
  );
}
