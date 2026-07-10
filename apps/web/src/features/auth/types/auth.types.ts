export type UserStatus = 'active' | 'disabled';

export type PublicUser = {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: UserStatus;
  createdAt: string;
};

export type AuthResponse = {
  user: PublicUser;
};

export type LoginInput = {
  username: string;
  password: string;
};
