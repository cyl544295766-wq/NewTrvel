import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { TripForm } from '../components/TripForm';
import { useTrip, useUpdateTrip } from '../hooks/useTrips';
import { TripUpdateInput } from '../types/trip.types';

export function EditTripPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const navigate = useNavigate();
  const trip = useTrip(tripId ?? '', Boolean(tripId));
  const updateTrip = useUpdateTrip(tripId ?? '');
  const data = trip.data?.trip;

  if (!tripId) {
    return <Navigate replace to="/" />;
  }

  async function handleSubmit(input: TripUpdateInput) {
    const result = await updateTrip.mutateAsync(input);
    navigate(`/trips/${result.trip.id}`, { replace: true });
  }

  if (trip.isLoading) {
    return <main className="loading-shell">Loading...</main>;
  }

  if (trip.isError || !data) {
    return <Navigate replace to="/" />;
  }

  return (
    <main className="app-page narrow-page">
      <Link className="text-link" to={`/trips/${data.id}`}>
        返回旅行详情
      </Link>
      <section className="content-panel">
        <p className="eyebrow">编辑旅行</p>
        <h1>{data.title}</h1>
        <TripForm
          includeStatus
          isSubmitting={updateTrip.isPending}
          onSubmit={handleSubmit}
          trip={data}
        />
      </section>
    </main>
  );
}
