export type Photo = {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  url: string;
  caption: string | null;
  shotAt: string | null;
  isCover: boolean;
  createdAt: string;
  updatedAt: string;
  tripDay: { id: string; dayIndex: number; date: string } | null;
  tripPlace: { id: string; name: string } | null;
};

export type PhotoInput = {
  url: string;
  caption?: string;
  shotAt?: string;
  tripDayId?: string;
  tripPlaceId?: string;
  isCover?: boolean;
};

export type PhotoUpdateInput = {
  caption?: string;
  shotAt?: string | null;
  tripDayId?: string | null;
  tripPlaceId?: string | null;
  isCover?: boolean;
};
