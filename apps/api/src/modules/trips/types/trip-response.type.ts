import { TripMemberRole, TripStatus } from '@prisma/client';

export type TripResponse = {
  id: string;
  title: string;
  description: string | null;
  destination: string | null;
  startDate: Date | null;
  endDate: Date | null;
  status: TripStatus;
  coverImageUrl: string | null;
  budget: string | null;
  ownerId: string;
  currentUserRole: TripMemberRole;
  isFavorite: boolean;
  archivedAt: Date | null;
  deletedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
};
