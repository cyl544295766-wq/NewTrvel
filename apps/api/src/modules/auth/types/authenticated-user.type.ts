export type AuthenticatedUser = {
  id: string;
  username: string;
};

export type AuthTokenPayload = {
  sub: string;
  username: string;
};
