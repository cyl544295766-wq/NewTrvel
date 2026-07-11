import {
  DndContext,
  DragEndEvent,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { arrayMove } from '@dnd-kit/sortable';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useTrip } from '../../trips/hooks/useTrips';
import { useTripWeather } from '../../weather';
import { TripDayCard } from '../components/TripDayCard';
import {
  useCreateTripPlace,
  useDeleteTripPlace,
  useGenerateTripDays,
  useMoveTripPlace,
  useReorderTripPlaces,
  useTripDays,
  useUpdateTripDay,
  useUpdateTripPlace,
} from '../hooks/useItinerary';
import { TripDay, TripPlace, TripPlaceInput } from '../types/itinerary.types';

export function ItineraryPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const trip = useTrip(safeTripId, Boolean(tripId));
  const weather = useTripWeather(safeTripId, Boolean(tripId));
  const days = useTripDays(safeTripId);
  const generateDays = useGenerateTripDays(safeTripId);
  const updateDay = useUpdateTripDay(safeTripId);
  const createPlace = useCreateTripPlace(safeTripId);
  const updatePlace = useUpdateTripPlace(safeTripId);
  const movePlace = useMoveTripPlace(safeTripId);
  const reorderPlaces = useReorderTripPlaces(safeTripId);
  const deletePlace = useDeleteTripPlace(safeTripId);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  if (!tripId) return <Navigate replace to="/" />;
  const currentTrip = trip.data?.trip;
  const canEdit = currentTrip
    ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole)
    : false;
  const tripDays = days.data?.days ?? [];

  async function handleUpdateDay(dayId: string, title: string, summary: string) {
    await updateDay.mutateAsync({ dayId, title, summary });
  }

  async function handleCreatePlace(input: TripPlaceInput) {
    await createPlace.mutateAsync(input);
  }

  function handleTogglePlaceCompleted(place: TripPlace) {
    updatePlace.mutate({
      placeId: place.id,
      input: { isCompleted: !place.isCompleted },
    });
  }

  async function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const activePlaceId = String(active.id);
    const sourceDay = findDayByPlaceId(tripDays, activePlaceId);
    const targetDay = findTargetDay(tripDays, String(over.id));

    if (!sourceDay || !targetDay) return;

    if (sourceDay.id === targetDay.id) {
      const oldIndex = sourceDay.places.findIndex((place) => place.id === activePlaceId);
      const newIndex = sourceDay.places.findIndex((place) => place.id === String(over.id));
      if (oldIndex < 0 || newIndex < 0 || oldIndex === newIndex) return;
      const nextPlaceIds = arrayMove(
        sourceDay.places.map((place) => place.id),
        oldIndex,
        newIndex,
      );
      await reorderPlaces.mutateAsync({ dayId: sourceDay.id, placeIds: nextPlaceIds });
      return;
    }

    await movePlace.mutateAsync({ placeId: activePlaceId, tripDayId: targetDay.id });
    await reorderPlaces.mutateAsync({
      dayId: targetDay.id,
      placeIds: [...targetDay.places.map((place) => place.id), activePlaceId],
    });
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
            <h1>行程规划</h1>
          </div>
          {canEdit ? (
            <button onClick={() => generateDays.mutate()} type="button">
              生成行程天数
            </button>
          ) : null}
        </div>
        {days.isLoading ? <p>加载中...</p> : null}
        {tripDays.length === 0 ? <p className="empty-state">暂无行程天数</p> : null}
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <div className="day-list">
            {tripDays.map((day) => (
              <TripDayCard
                canEdit={canEdit}
                day={day}
                isCreatingPlace={createPlace.isPending}
                key={day.id}
                onCreatePlace={handleCreatePlace}
                onDeletePlace={(placeId) => deletePlace.mutate(placeId)}
                onTogglePlaceCompleted={handleTogglePlaceCompleted}
                onUpdateDay={handleUpdateDay}
                weather={weather.data?.weather.find(
                  (item) =>
                    item.tripDayId === day.id || item.date.slice(0, 10) === day.date.slice(0, 10),
                )}
                searchCity={currentTrip?.destination ?? undefined}
              />
            ))}
          </div>
        </DndContext>
      </section>
    </main>
  );
}

function findDayByPlaceId(days: TripDay[], placeId: string) {
  return days.find((day) => day.places.some((place) => place.id === placeId));
}

function findTargetDay(days: TripDay[], overId: string) {
  if (overId.startsWith('day:')) {
    return days.find((day) => `day:${day.id}` === overId);
  }

  return findDayByPlaceId(days, overId);
}
