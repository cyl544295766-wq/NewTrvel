import { Link, Navigate, useParams } from 'react-router-dom';
import { useTrip } from '../../trips/hooks/useTrips';
import { TripDayCard } from '../components/TripDayCard';
import {
  useCreateTripPlace,
  useDeleteTripPlace,
  useGenerateTripDays,
  useTripDays,
  useUpdateTripDay,
} from '../hooks/useItinerary';
import { TripPlaceInput } from '../types/itinerary.types';

export function ItineraryPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const trip = useTrip(safeTripId, Boolean(tripId));
  const days = useTripDays(safeTripId);
  const generateDays = useGenerateTripDays(safeTripId);
  const updateDay = useUpdateTripDay(safeTripId);
  const createPlace = useCreateTripPlace(safeTripId);
  const deletePlace = useDeleteTripPlace(safeTripId);

  if (!tripId) return <Navigate replace to="/" />;
  const currentTrip = trip.data?.trip;
  const canEdit = currentTrip
    ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole)
    : false;

  async function handleUpdateDay(dayId: string, title: string, summary: string) {
    await updateDay.mutateAsync({ dayId, title, summary });
  }

  async function handleCreatePlace(input: TripPlaceInput) {
    await createPlace.mutateAsync(input);
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">行程</p>
            <h1>{currentTrip?.title ?? '旅行行程'}</h1>
          </div>
          {canEdit ? (
            <button onClick={() => generateDays.mutate()} type="button">
              生成行程天数
            </button>
          ) : null}
        </div>
        {days.isLoading ? <p>加载中...</p> : null}
        {days.data?.days.length === 0 ? <p className="empty-state">暂无行程天数</p> : null}
        <div className="day-list">
          {days.data?.days.map((day) => (
            <TripDayCard
              canEdit={canEdit}
              day={day}
              isCreatingPlace={createPlace.isPending}
              key={day.id}
              onCreatePlace={handleCreatePlace}
              onDeletePlace={(placeId) => deletePlace.mutate(placeId)}
              onUpdateDay={handleUpdateDay}
            />
          ))}
        </div>
      </section>
    </main>
  );
}
