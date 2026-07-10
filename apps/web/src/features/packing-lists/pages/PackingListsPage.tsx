import { useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { PackingListForm } from '../components/PackingListForm';
import { PackingListList } from '../components/PackingListList';
import {
  useCreatePackingItem,
  useCreatePackingList,
  useDeletePackingItem,
  useDeletePackingList,
  useDuplicatePackingList,
  usePackingLists,
  useUpdatePackingItem,
  useUpdatePackingList,
} from '../hooks/usePackingLists';
import { PackingItemInput, PackingListInput } from '../types/packing-list.types';

export function PackingListsPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const safeTripId = tripId ?? '';
  const lists = usePackingLists(safeTripId);
  const createList = useCreatePackingList(safeTripId);
  const updateList = useUpdatePackingList(safeTripId);
  const deleteList = useDeletePackingList(safeTripId);
  const duplicateList = useDuplicatePackingList(safeTripId);
  const createItem = useCreatePackingItem(safeTripId);
  const updateItem = useUpdatePackingItem(safeTripId);
  const deleteItem = useDeletePackingItem(safeTripId);
  const [showListForm, setShowListForm] = useState(false);
  const isSaving =
    createList.isPending ||
    updateList.isPending ||
    deleteList.isPending ||
    duplicateList.isPending ||
    createItem.isPending ||
    updateItem.isPending ||
    deleteItem.isPending;

  if (!tripId) return <Navigate replace to="/" />;

  async function handleCreateList(input: PackingListInput) {
    await createList.mutateAsync(input);
    setShowListForm(false);
  }

  async function handleUpdateItem(listId: string, itemId: string, input: PackingItemInput) {
    await updateItem.mutateAsync({ listId, itemId, input });
  }

  return (
    <main className="app-page">
      <Link className="text-link" to={`/trips/${safeTripId}`}>
        返回旅行详情
      </Link>
      <section className="content-panel itinerary-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">出行准备</p>
            <h1>打包清单</h1>
          </div>
          <button onClick={() => setShowListForm((current) => !current)} type="button">
            新建清单
          </button>
        </div>

        {showListForm ? (
          <PackingListForm
            isSubmitting={createList.isPending}
            onCancel={() => setShowListForm(false)}
            onSubmit={handleCreateList}
          />
        ) : null}

        {lists.isLoading ? <p>加载中...</p> : null}
        {lists.isError ? <p className="form-error">打包清单加载失败</p> : null}
        {lists.data ? (
          <PackingListList
            isSaving={isSaving}
            lists={lists.data.packingLists}
            onCreateItem={(listId, input) =>
              createItem.mutateAsync({ listId, input }).then(() => {})
            }
            onDelete={(listId) => deleteList.mutateAsync(listId).then(() => {})}
            onDeleteItem={(listId, itemId) =>
              deleteItem.mutateAsync({ listId, itemId }).then(() => {})
            }
            onDuplicate={(listId) => duplicateList.mutateAsync(listId).then(() => {})}
            onToggleItem={(listId, itemId, isPacked) =>
              updateItem.mutateAsync({ listId, itemId, input: { isPacked } }).then(() => {})
            }
            onUpdate={(listId, input) => updateList.mutateAsync({ listId, input }).then(() => {})}
            onUpdateItem={handleUpdateItem}
          />
        ) : null}
      </section>
    </main>
  );
}
