import { useState } from 'react';
import { ArrowLeft, Compass } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { TripForm } from '../components/TripForm';
import { useCreateTrip } from '../hooks/useTrips';
import { TripUpdateInput } from '../types/trip.types';

export function NewTripPage() {
  const navigate = useNavigate();
  const createTrip = useCreateTrip();
  const [destination, setDestination] = useState('');
  const backgroundUrl = destination
    ? `https://picsum.photos/seed/${encodeURIComponent(destination)}/1200/1600`
    : 'https://picsum.photos/seed/travel-editorial-coast/1200/1600';

  async function handleSubmit(input: TripUpdateInput) {
    const result = await createTrip.mutateAsync(input);
    navigate(`/trips/${result.trip.id}`, { replace: true });
  }

  return (
    <main className="new-trip-page">
      <aside className="new-trip-visual" style={{ backgroundImage: `url(${backgroundUrl})` }}>
        <div className="new-trip-visual-shade" />
        <Link className="new-trip-return" to="/"><ArrowLeft size={17} />返回旅行列表</Link>
        <div className="new-trip-brand"><Compass size={19} /><span>TRAVEL OS</span></div>
        <blockquote><p>{destination ? `计划一场 ${destination} 之旅` : '每一次旅行，都从一次想象开始'}</p><footer>YOUR NEXT CHAPTER · 2026</footer></blockquote>
      </aside>
      <section className="new-trip-workspace">
        <div className="new-trip-form-shell">
          <header className="new-trip-heading"><p>TRAVEL PLANNER · NEW JOURNEY</p><h1>Plan a new trip</h1><span>创建一本属于你的旅行计划册</span></header>
          <TripForm isSubmitting={createTrip.isPending} mode="editorial" onDestinationChange={setDestination} onSubmit={handleSubmit} />
        </div>
      </section>
    </main>
  );
}
