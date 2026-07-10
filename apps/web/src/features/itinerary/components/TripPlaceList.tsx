import { TripPlace } from '../types/itinerary.types';

type Props = { places: TripPlace[]; onDelete: (placeId: string) => void; canEdit: boolean };

export function TripPlaceList({ places, onDelete, canEdit }: Props) {
  if (places.length === 0) return <p className="muted-text">暂无地点</p>;
  return (
    <div className="place-list">
      {places.map((place) => (
        <div className="place-item" key={place.id}>
          <div>
            <strong>{place.name}</strong>
            <p>{place.address || '暂无地址'}</p>
            {place.notes ? <p>{place.notes}</p> : null}
          </div>
          {canEdit ? (
            <button className="secondary-button" onClick={() => onDelete(place.id)} type="button">
              删除
            </button>
          ) : null}
        </div>
      ))}
    </div>
  );
}
