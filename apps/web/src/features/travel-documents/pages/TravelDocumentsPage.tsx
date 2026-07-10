import { useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useTripDays } from '../../itinerary/hooks/useItinerary';
import { DocumentDetailModal } from '../components/DocumentDetailModal';
import { DocumentList } from '../components/DocumentList';
import { DocumentUploadForm } from '../components/DocumentUploadForm';
import {
  useCreateTravelDocument,
  useDeleteTravelDocument,
  useTravelDocuments,
  useUpdateTravelDocument,
} from '../hooks/useTravelDocuments';
import {
  TravelDocument,
  TravelDocumentInput,
  TravelDocumentUpdateInput,
} from '../types/travel-document.types';

export function TravelDocumentsPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const days = useTripDays(safeTripId);
  const documents = useTravelDocuments(safeTripId);
  const createDocument = useCreateTravelDocument(safeTripId);
  const updateDocument = useUpdateTravelDocument(safeTripId);
  const deleteDocument = useDeleteTravelDocument(safeTripId);
  const [showUploadForm, setShowUploadForm] = useState(false);
  const [selectedDocument, setSelectedDocument] = useState<TravelDocument | null>(null);

  if (!tripId) return <Navigate replace to="/" />;

  async function handleUpload(input: TravelDocumentInput) {
    await createDocument.mutateAsync(input);
    setShowUploadForm(false);
  }

  async function handleSave(documentId: string, input: TravelDocumentUpdateInput) {
    const result = await updateDocument.mutateAsync({ documentId, input });
    setSelectedDocument(result.document);
  }

  async function handleDelete(documentId: string) {
    await deleteDocument.mutateAsync(documentId);
    setSelectedDocument(null);
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">旅行文档</p>
            <h1>文档中心</h1>
          </div>
          <button onClick={() => setShowUploadForm((current) => !current)} type="button">
            上传文档
          </button>
        </div>
        {showUploadForm ? (
          <DocumentUploadForm
            days={days.data?.days ?? []}
            isSubmitting={createDocument.isPending}
            onSubmit={handleUpload}
          />
        ) : null}
        {documents.isLoading ? <p>加载中...</p> : null}
        {documents.isError ? <p className="form-error">文档加载失败</p> : null}
        {documents.data ? (
          <DocumentList
            documents={documents.data.documents}
            onSelect={(document) => setSelectedDocument(document)}
          />
        ) : null}
      </section>
      {selectedDocument ? (
        <DocumentDetailModal
          days={days.data?.days ?? []}
          document={selectedDocument}
          isDeleting={deleteDocument.isPending}
          isSaving={updateDocument.isPending}
          onClose={() => setSelectedDocument(null)}
          onDelete={handleDelete}
          onSave={handleSave}
        />
      ) : null}
    </main>
  );
}
