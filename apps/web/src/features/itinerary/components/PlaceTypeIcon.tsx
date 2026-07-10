import { TripPlaceType } from '../types/itinerary.types';

export const typeLabels: Record<TripPlaceType, { label: string; icon: string }> = {
  attraction: { label: '景点', icon: '⛰️' },
  hotel: { label: '酒店', icon: '🏨' },
  restaurant: { label: '餐厅', icon: '🍽️' },
  transport: { label: '交通', icon: '🚗' },
  shopping: { label: '购物', icon: '🛍️' },
  custom: { label: '自定义', icon: '📍' },
};

type Props = {
  type: TripPlaceType;
};

export function PlaceTypeIcon({ type }: Props) {
  const item = typeLabels[type];

  return (
    <span className="place-type-badge">
      <span aria-hidden="true">{item.icon}</span>
      {item.label}
    </span>
  );
}
