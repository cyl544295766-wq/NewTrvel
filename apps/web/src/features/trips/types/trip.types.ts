export type TripStatus = 'draft' | 'planning' | 'active' | 'completed' | 'archived';
export type TripMemberRole = 'owner' | 'admin' | 'member' | 'viewer';

export type Trip = {
  id: string;
  title: string;
  description: string | null;
  destination: string | null;
  startDate: string | null;
  endDate: string | null;
  status: TripStatus;
  coverImageUrl: string | null;
  budget: string | null;
  ownerId: string;
  currentUserRole: TripMemberRole;
  isFavorite: boolean;
  archivedAt: string | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type TripInput = {
  title: string;
  description?: string;
  destination?: string;
  startDate?: string;
  endDate?: string;
  coverImageUrl?: string;
};

export type TripUpdateInput = Partial<TripInput> & {
  status?: TripStatus;
  budget?: string | null;
};
