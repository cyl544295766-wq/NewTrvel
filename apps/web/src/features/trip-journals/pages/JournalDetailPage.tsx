import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { useTripDays } from '../../itinerary/hooks/useItinerary';
import { usePhotos } from '../../photos/hooks/usePhotos';
import { JournalCard } from '../components/JournalCard';
import { JournalForm, JournalFormValues } from '../components/JournalForm';
import {
  useDeleteTripJournal,
  useTripJournal,
  useUpdateTripJournal,
} from '../hooks/useTripJournals';

export function JournalDetailPage() {
  const { tripId, journalId } = useParams<{ tripId: string; journalId: string }>();
  const safeTripId = tripId ?? '';
  const safeJournalId = journalId ?? '';
  const navigate = useNavigate();
  const journal = useTripJournal(safeTripId, safeJournalId);
  const days = useTripDays(safeTripId);
  const photos = usePhotos(safeTripId);
  const updateJournal = useUpdateTripJournal(safeTripId);
  const deleteJournal = useDeleteTripJournal(safeTripId);
  const [isEditing, setIsEditing] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);

  if (!tripId || !journalId) return <Navigate replace to="/" />;
  if (journal.isLoading) return <main className="loading-shell">游记加载中...</main>;
  if (journal.isError || !journal.data)
    return <Navigate replace to={`/trips/${tripId}/journals`} />;
  const currentJournal = journal.data.journal;

  async function handleUpdate(input: JournalFormValues) {
    await updateJournal.mutateAsync({ journalId: safeJournalId, input });
    setIsEditing(false);
  }

  async function handleDelete() {
    await deleteJournal.mutateAsync(safeJournalId);
    navigate(`/trips/${safeTripId}/journals`, { replace: true });
  }

  return (
    <main className="app-page journal-detail-page">
      <Link className="text-link" to={`/trips/${tripId}/journals`}>
        返回游记列表
      </Link>
      <section className="content-panel">
        <div className="panel-heading journal-detail-actions">
          <div>
            <p className="eyebrow">旅行游记</p>
            <h1>{currentJournal.title}</h1>
          </div>
          <div className="form-actions">
            {currentJournal.isDraft ? (
              <button
                disabled={updateJournal.isPending}
                onClick={() =>
                  void updateJournal.mutateAsync({
                    journalId: safeJournalId,
                    input: { isDraft: false },
                  })
                }
                type="button"
              >
                发布
              </button>
            ) : null}
            <button className="secondary-button" onClick={() => setIsEditing(true)} type="button">
              编辑
            </button>
            <button
              className="secondary-button danger-button"
              onClick={() => setIsDeleteOpen(true)}
              type="button"
            >
              删除
            </button>
          </div>
        </div>
        {isEditing ? (
          <JournalForm
            days={days.data?.days ?? []}
            initialJournal={currentJournal}
            isSubmitting={updateJournal.isPending}
            onCancel={() => setIsEditing(false)}
            onSubmit={handleUpdate}
            photos={photos.data?.photos ?? []}
          />
        ) : (
          <JournalCard journal={currentJournal} />
        )}
      </section>
      <ConfirmDialog
        cancelLabel="取消"
        confirmLabel="删除"
        content={`“${currentJournal.title}”将被永久删除，关联照片不会被删除。`}
        isOpen={isDeleteOpen}
        isPending={deleteJournal.isPending}
        onCancel={() => setIsDeleteOpen(false)}
        onConfirm={() => void handleDelete()}
        title="删除游记？"
      />
    </main>
  );
}
