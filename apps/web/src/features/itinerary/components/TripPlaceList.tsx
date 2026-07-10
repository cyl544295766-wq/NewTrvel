import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { PlaceTypeIcon } from './PlaceTypeIcon';
import { TripPlace } from '../types/itinerary.types';

type Props = {
  canEdit: boolean;
  dayId: string;
  places: TripPlace[];
  onDelete: (placeId: string) => void;
  onToggleCompleted: (place: TripPlace) => void;
};

export function TripPlaceList({ canEdit, dayId, places, onDelete, onToggleCompleted }: Props) {
  return (
    <SortableContext
      id={dayId}
      items={places.map((place) => place.id)}
      strategy={verticalListSortingStrategy}
    >
      <div className="place-list">
        {places.length === 0 ? (
          <p className="empty-state compact-empty">这一天还没有安排，添加一个地点吧</p>
        ) : null}
        {places.map((place) => (
          <SortablePlaceItem
            canEdit={canEdit}
            key={place.id}
            onDelete={onDelete}
            onToggleCompleted={onToggleCompleted}
            place={place}
          />
        ))}
      </div>
    </SortableContext>
  );
}

function SortablePlaceItem({
  canEdit,
  onDelete,
  onToggleCompleted,
  place,
}: {
  canEdit: boolean;
  onDelete: (placeId: string) => void;
  onToggleCompleted: (place: TripPlace) => void;
  place: TripPlace;
}) {
  const {
    attributes,
    isDragging,
    listeners,
    setActivatorNodeRef,
    setNodeRef,
    transform,
    transition,
  } = useSortable({
    id: place.id,
    data: { dayId: place.tripDayId },
    disabled: !canEdit,
  });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <article
      className={isDragging ? 'place-item dragging' : 'place-item'}
      ref={setNodeRef}
      style={style}
    >
      <div className={place.isCompleted ? 'place-content completed' : 'place-content'}>
        <div className="place-title-row">
          <strong>{place.name}</strong>
          <PlaceTypeIcon type={place.type} />
        </div>
        <p>{place.address || '暂无地址'}</p>
        <p>{place.isCompleted ? '已完成' : '未完成'}</p>
        {place.notes ? <p>{place.notes}</p> : null}
      </div>
      {canEdit ? (
        <div className="place-actions">
          <label className="inline-checkbox">
            <input
              checked={place.isCompleted}
              onChange={() => onToggleCompleted(place)}
              type="checkbox"
            />
            已完成
          </label>
          <button
            className="secondary-button"
            ref={setActivatorNodeRef}
            type="button"
            {...attributes}
            {...listeners}
          >
            拖动
          </button>
          <button className="secondary-button" onClick={() => onDelete(place.id)} type="button">
            删除
          </button>
        </div>
      ) : null}
    </article>
  );
}
