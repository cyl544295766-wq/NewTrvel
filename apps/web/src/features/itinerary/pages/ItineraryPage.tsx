import { DndContext, DragEndEvent, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core';
import { arrayMove } from '@dnd-kit/sortable';
import { ArrowUpRight, Map as MapIcon, Plus } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { TripMap } from '../../map/components/TripMap';
import { TripRouteDay } from '../../map/types/map.types';
import { ExportPdfButton } from '../../trip-pdf';
import { useTrip } from '../../trips/hooks/useTrips';
import { useTripWeather } from '../../weather';
import { ItinerarySidebar } from '../components/ItinerarySidebar';
import { TripDayCard } from '../components/TripDayCard';
import { useCreateTripPlace, useDeleteTripPlace, useGenerateTripDays, useMoveTripPlace, useReorderTripPlaces, useTripDays, useUpdateTripDay, useUpdateTripPlace } from '../hooks/useItinerary';
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
  const [activeDayId, setActiveDayId] = useState<string | null>(null);
  const [selectedPlaceId, setSelectedPlaceId] = useState<string | null>(null);
  const [openFormDayId, setOpenFormDayId] = useState<string | null>(null);
  const [actionError, setActionError] = useState('');
  const dayRefs = useRef<Map<string, HTMLElement>>(new Map());
  const tripDays = days.data?.days ?? [];
  const currentTrip = trip.data?.trip;
  const canEdit = currentTrip ? ['owner', 'admin', 'member'].includes(currentTrip.currentUserRole) : false;

  useEffect(() => {
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      const id = visible?.target.getAttribute('data-day-id');
      if (id) setActiveDayId(id);
    }, { rootMargin: '-14% 0px -64% 0px', threshold: [0.1, 0.35, 0.7] });
    dayRefs.current.forEach((node) => observer.observe(node));
    return () => observer.disconnect();
  }, [tripDays]);

  const mapRoute = useMemo<TripRouteDay[]>(() => tripDays.map((day) => ({ dayId: day.id, dayIndex: day.dayIndex, date: day.date, places: day.places.map((place) => ({ id: place.id, name: place.name, type: place.type, latitude: place.latitude ?? '', longitude: place.longitude ?? '', sortOrder: place.sortOrder, address: place.address, notes: place.notes, startTime: place.startTime })) })), [tripDays]);
  const sidebarRoute = activeDayId ? mapRoute.filter((day) => day.dayId === activeDayId) : mapRoute;
  const allPlaces = tripDays.flatMap((day) => day.places);
  const completedPlaces = allPlaces.filter((place) => place.isCompleted).length;

  if (!tripId) return <Navigate replace to="/" />;
  if (trip.isLoading || days.isLoading) return <main className="loading-shell">正在打开行程手册...</main>;
  if (trip.isError || !currentTrip) return <Navigate replace to="/" />;

  function selectDay(dayId: string) {
    setActionError('');
    setActiveDayId(dayId);
    document.getElementById(`itinerary-day-${dayId}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function selectPlace(placeId: string) {
    setSelectedPlaceId(placeId);
    const element = document.querySelector(`[data-place-id="${placeId}"]`);
    element?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  async function addPlace() {
    setActionError('');
    const existingDayId = activeDayId ?? tripDays[0]?.id;
    if (existingDayId) {
      setOpenFormDayId(existingDayId);
      selectDay(existingDayId);
      return;
    }

    if (!canEdit || generateDays.isPending) return;
    try {
      const generated = await generateDays.mutateAsync();
      const firstDay = generated.days[0];
      if (!firstDay) {
        setActionError('请先为旅行设置开始和结束日期');
        return;
      }
      setOpenFormDayId(firstDay.id);
      setActiveDayId(firstDay.id);
      requestAnimationFrame(() => {
        document.getElementById(`itinerary-day-${firstDay.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    } catch {
      setActionError('日程日期生成失败，请稍后重试');
    }
  }

  async function handleUpdateDay(dayId: string, title: string, summary: string) { await updateDay.mutateAsync({ dayId, title, summary }); }
  async function handleCreatePlace(input: TripPlaceInput) { await createPlace.mutateAsync(input); }
  async function handleUpdatePlace(placeId: string, input: Partial<TripPlaceInput>) { await updatePlace.mutateAsync({ placeId, input }); }
  function handleTogglePlaceCompleted(place: TripPlace) { updatePlace.mutate({ placeId: place.id, input: { isCompleted: !place.isCompleted } }); }

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
      await reorderPlaces.mutateAsync({ dayId: sourceDay.id, placeIds: arrayMove(sourceDay.places.map((place) => place.id), oldIndex, newIndex) });
      return;
    }
    await movePlace.mutateAsync({ placeId: activePlaceId, tripDayId: targetDay.id });
    await reorderPlaces.mutateAsync({ dayId: targetDay.id, placeIds: [...targetDay.places.map((place) => place.id), activePlaceId] });
  }

  return (
    <main className="itinerary-editorial-page">
      <ItinerarySidebar activeDayId={activeDayId} completedPlaces={completedPlaces} days={tripDays} onAddPlace={addPlace} onDaySelect={selectDay} totalPlaces={allPlaces.length} trip={currentTrip} weather={weather.data?.weather ?? []} />
      <section className="itinerary-main-column">
        <header className="itinerary-page-header">
          <div><Link className="itinerary-back-link" to={`/trips/${safeTripId}`}>返回旅行详情</Link><p className="itinerary-overline">THE JOURNEY AHEAD</p><h1>行程日程</h1><p className="itinerary-header-subtitle">{currentTrip.destination || '未命名旅程'} <span>·</span> {formatRange(currentTrip.startDate, currentTrip.endDate)}</p></div>
          <div className="itinerary-header-actions"><Link className="itinerary-header-button" to={`/trips/${safeTripId}/map`}><MapIcon size={16} />地图视图</Link><ExportPdfButton compact tripId={safeTripId} />{canEdit && tripDays.length === 0 ? <button className="itinerary-header-button" disabled={generateDays.isPending} onClick={() => { setActionError(''); generateDays.mutate(); }} type="button">{generateDays.isPending ? '生成中...' : '生成日期'}</button> : null}<button className="itinerary-primary-action" disabled={generateDays.isPending} onClick={addPlace} type="button"><Plus size={17} />{generateDays.isPending ? '准备日程...' : '添加地点'}</button></div>
        </header>
        {actionError ? <p className="itinerary-action-error" role="alert">{actionError}</p> : null}
        <div className="itinerary-content-grid">
          <DndContext collisionDetection={closestCenter} onDragEnd={handleDragEnd} sensors={sensors}>
            <div className="itinerary-timeline" aria-label="旅行日程">
              {tripDays.length === 0 ? <div className="itinerary-empty-state"><Plus size={26} /><h2>还没有安排日程</h2><p>从第一个地点开始，慢慢铺陈这段旅程。</p><button onClick={addPlace} type="button">添加第一个地点</button></div> : tripDays.map((day) => <div className="itinerary-day-wrap" key={day.id} ref={(node: HTMLDivElement | null) => { if (node) dayRefs.current.set(day.id, node); else dayRefs.current.delete(day.id); }}><TripDayCard canEdit={canEdit} day={day} isCreatingPlace={createPlace.isPending} onCreatePlace={handleCreatePlace} onDeletePlace={(placeId) => deletePlace.mutate(placeId)} onFormOpenChange={(open) => { if (!open) setOpenFormDayId(null); }} onSelectPlace={selectPlace} onTogglePlaceCompleted={handleTogglePlaceCompleted} onUpdateDay={handleUpdateDay} onUpdatePlace={handleUpdatePlace} openForm={openFormDayId === day.id} selectedPlaceId={selectedPlaceId} weather={weather.data?.weather.find((item) => item.tripDayId === day.id || item.date.slice(0, 10) === day.date.slice(0, 10))} /></div>) }
            </div>
          </DndContext>
          <aside className="itinerary-map-rail"><div className="itinerary-map-rail-heading"><div><p className="itinerary-overline">A SENSE OF PLACE</p><h2>{activeDayId ? `第 ${tripDays.find((day) => day.id === activeDayId)?.dayIndex ?? 1} 天路线` : '全部路线'}</h2></div><ArrowUpRight size={17} /></div><div className="itinerary-mini-map"><TripMap activeDayId={activeDayId} compact onSelectPlace={(placeId) => { if (placeId) selectPlace(placeId); else setSelectedPlaceId(null); }} route={sidebarRoute} selectedPlaceId={selectedPlaceId} /></div><p className="itinerary-map-caption">点击地点卡片，地图会跟随你的视线。</p></aside>
        </div>
      </section>
    </main>
  );
}

function findDayByPlaceId(days: TripDay[], placeId: string) { return days.find((day) => day.places.some((place) => place.id === placeId)); }
function findTargetDay(days: TripDay[], overId: string) { return overId.startsWith('day:') ? days.find((day) => `day:${day.id}` === overId) : findDayByPlaceId(days, overId); }
function formatRange(start: string | null, end: string | null) { if (!start && !end) return '日期待定'; return `${start?.slice(0, 10) ?? '待定'} — ${end?.slice(0, 10) ?? '待定'}`; }
