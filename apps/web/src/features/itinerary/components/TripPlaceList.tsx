import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Check, GripVertical, MapPin, PenLine, Save, Trash2, X } from 'lucide-react';
import { useState } from 'react';
import { PlaceTypeIcon } from './PlaceTypeIcon';
import { TripPlace, TripPlaceInput } from '../types/itinerary.types';

type Props = {
  canEdit: boolean;
  dayId: string;
  places: TripPlace[];
  onDelete: (placeId: string) => void;
  onToggleCompleted: (place: TripPlace) => void;
  onSelectPlace?: (placeId: string) => void;
  onUpdate?: (placeId: string, input: Partial<TripPlaceInput>) => Promise<void>;
  selectedPlaceId?: string | null;
};

export function TripPlaceList({ canEdit, dayId, places, onDelete, onToggleCompleted, onSelectPlace, onUpdate, selectedPlaceId }: Props) {
  return (
    <SortableContext id={dayId} items={places.map((place) => place.id)} strategy={verticalListSortingStrategy}>
      <div className="place-list">
        {places.length === 0 ? <div className="empty-place-state"><MapPin size={20} /><strong>这一天还没有安排</strong><span>添加第一个地点，开始铺陈你的旅程。</span></div> : null}
        {places.map((place) => <SortablePlaceItem canEdit={canEdit} isSelected={selectedPlaceId === place.id} key={place.id} onDelete={onDelete} onSelectPlace={onSelectPlace} onToggleCompleted={onToggleCompleted} onUpdate={onUpdate} place={place} />)}
      </div>
    </SortableContext>
  );
}

function SortablePlaceItem({ canEdit, isSelected, onDelete, onSelectPlace, onToggleCompleted, onUpdate, place }: {
  canEdit: boolean;
  isSelected: boolean;
  onDelete: (placeId: string) => void;
  onSelectPlace?: (placeId: string) => void;
  onToggleCompleted: (place: TripPlace) => void;
  onUpdate?: (placeId: string, input: Partial<TripPlaceInput>) => Promise<void>;
  place: TripPlace;
}) {
  const { attributes, isDragging, listeners, setActivatorNodeRef, setNodeRef, transform, transition } = useSortable({ id: place.id, data: { dayId: place.tripDayId }, disabled: !canEdit });
  const style = { transform: CSS.Transform.toString(transform), transition };
  const [isEditing, setIsEditing] = useState(false);
  const [name, setName] = useState(place.name);
  const [address, setAddress] = useState(place.address ?? '');
  const [notes, setNotes] = useState(place.notes ?? '');
  const [isSaving, setIsSaving] = useState(false);
  const duration = getDuration(place.startTime, place.endTime);

  return (
    <article aria-current={isSelected ? 'location' : undefined} className={`place-item${isDragging ? ' dragging' : ''}${isSelected ? ' selected' : ''}`} data-place-id={place.id} onClick={(event) => { if (!(event.target as HTMLElement).closest('button, input')) onSelectPlace?.(place.id); }} ref={setNodeRef} style={style}>
      <div className="place-time-column"><time>{formatTime(place.startTime)}</time>{duration ? <span>{duration}</span> : <span>时间待定</span>}</div>
      <div className={place.isCompleted ? 'place-content completed' : 'place-content'}>
        <div className="place-title-row"><span className={`place-type-orb place-type-${place.type}`}><PlaceTypeIcon type={place.type} /></span><div><strong>{place.name}</strong><span className="place-location-label">{place.address || '地址待补充'}</span></div></div>
        {place.notes ? <p className="place-notes">{place.notes}</p> : null}
        <p className="place-status-line">{place.isCompleted ? <><Check size={14} />已完成</> : <><MapPin size={14} />计划中</>}</p>
        {isEditing ? <form className="place-edit-form" onSubmit={async (event) => { event.preventDefault(); if (!onUpdate) return; setIsSaving(true); await onUpdate(place.id, { name: name.trim(), address: address.trim() || undefined, notes: notes.trim() || undefined }); setIsSaving(false); setIsEditing(false); }}><input aria-label="地点名称" maxLength={120} onChange={(event) => setName(event.target.value)} value={name} /><input aria-label="地点地址" maxLength={300} onChange={(event) => setAddress(event.target.value)} placeholder="地址" value={address} /><textarea aria-label="地点备注" maxLength={1000} onChange={(event) => setNotes(event.target.value)} placeholder="备注" rows={2} value={notes} /><div><button aria-label="保存地点" disabled={isSaving || !name.trim()} type="submit"><Save size={15} /></button><button aria-label="取消编辑" onClick={() => setIsEditing(false)} type="button"><X size={15} /></button></div></form> : null}
      </div>
      {canEdit ? <div className="place-actions">
        <label className="place-complete-toggle" title={place.isCompleted ? '取消完成' : '标记完成'}><input aria-label={`${place.isCompleted ? '取消完成' : '完成'}${place.name}`} checked={place.isCompleted} onChange={() => onToggleCompleted(place)} type="checkbox" /><span className="place-checkmark" aria-hidden="true"><Check size={13} /></span></label>
        <button aria-label={`拖动${place.name}`} className="icon-button drag-handle" ref={setActivatorNodeRef} type="button" {...attributes} {...listeners}><GripVertical size={17} /></button>
        {onUpdate ? <button aria-label={`编辑${place.name}`} className="icon-button" onClick={() => setIsEditing(true)} type="button"><PenLine size={16} /></button> : null}
        <button aria-label={`删除${place.name}`} className="icon-button danger-icon" onClick={() => onDelete(place.id)} type="button"><Trash2 size={16} /></button>
      </div> : null}
    </article>
  );
}

function formatTime(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '--:--'; }
function getDuration(start: string | null, end: string | null) { if (!start || !end) return ''; const minutes = Math.max(0, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 60000)); return minutes >= 60 ? `${Math.floor(minutes / 60)}小时${minutes % 60 ? ` ${minutes % 60}分` : ''}` : `${minutes}分钟`; }
