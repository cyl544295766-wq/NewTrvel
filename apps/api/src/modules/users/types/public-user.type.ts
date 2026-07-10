import { UserStatus } from '@prisma/client';

export type PublicUser = {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: UserStatus;
  createdAt: Date;
};
