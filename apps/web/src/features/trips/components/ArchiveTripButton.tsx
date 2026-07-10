import { useNavigate } from 'react-router-dom';
import { useArchiveTrip } from '../hooks/useTrips';

type ArchiveTripButtonProps = {
  tripId: string;
};

export function ArchiveTripButton({ tripId }: ArchiveTripButtonProps) {
  const navigate = useNavigate();
  const archiveTrip = useArchiveTrip();

  async function handleArchive() {
    await archiveTrip.mutateAsync(tripId);
    navigate('/', { replace: true });
  }

  return (
    <button
      className="secondary-button"
      disabled={archiveTrip.isPending}
      onClick={handleArchive}
      type="button"
    >
      {archiveTrip.isPending ? '归档中...' : '归档旅行'}
    </button>
  );
}
