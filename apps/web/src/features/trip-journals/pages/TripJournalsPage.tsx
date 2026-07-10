import { useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useTripDays } from '../../itinerary/hooks/useItinerary';
import { usePhotos } from '../../photos/hooks/usePhotos';
import { JournalForm, JournalFormValues } from '../components/JournalForm';
import { JournalList } from '../components/JournalList';
import { useCreateTripJournal, useTripJournals } from '../hooks/useTripJournals';
import { TripJournalInput } from '../types/trip-journal.types';

export function TripJournalsPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const journals = useTripJournals(safeTripId);
  const days = useTripDays(safeTripId);
  const photos = usePhotos(safeTripId);
  const createJournal = useCreateTripJournal(safeTripId);
  const [showForm, setShowForm] = useState(false);

  if (!tripId) return <Navigate replace to="/" />;

  async function handleCreate(values: JournalFormValues) {
    const input: TripJournalInput = {
      ...values,
      tripDayId: values.tripDayId ?? undefined,
      tripPlaceId: values.tripPlaceId ?? undefined,
      mood: values.mood ?? undefined,
    };
    await createJournal.mutateAsync(input);
    setShowForm(false);
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">旅途记录</p>
            <h1>旅行游记</h1>
          </div>
          <button onClick={() => setShowForm((current) => !current)} type="button">
            写游记
          </button>
        </div>
        {showForm ? (
          <JournalForm
            days={days.data?.days ?? []}
            isSubmitting={createJournal.isPending}
            onCancel={() => setShowForm(false)}
            onSubmit={handleCreate}
            photos={photos.data?.photos ?? []}
          />
        ) : null}
        {journals.isLoading ? <p>加载中...</p> : null}
        {journals.isError ? <p className="form-error">游记加载失败</p> : null}
        {journals.data ? <JournalList journals={journals.data.journals} tripId={tripId} /> : null}
      </section>
    </main>
  );
}
