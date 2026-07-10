import { Link, useNavigate } from 'react-router-dom';
import { TripForm } from '../components/TripForm';
import { useCreateTrip } from '../hooks/useTrips';
import { TripInput, TripUpdateInput } from '../types/trip.types';

export function NewTripPage() {
  const navigate = useNavigate();
  const createTrip = useCreateTrip();

  async function handleSubmit(input: TripUpdateInput) {
    const result = await createTrip.mutateAsync(input as TripInput);
    navigate(`/trips/${result.trip.id}`, { replace: true });
  }

  return (
    <main className="app-page narrow-page">
      <Link className="text-link" to="/">
        返回旅行列表
      </Link>
      <section className="content-panel">
        <p className="eyebrow">新建旅行</p>
        <h1>创建旅行</h1>
        <TripForm isSubmitting={createTrip.isPending} onSubmit={handleSubmit} />
      </section>
    </main>
  );
}
