import { request } from '../../../services/http';

export type TripMemberRole = 'owner' | 'admin' | 'member' | 'viewer';

export type TripMember = {
  id: string;
  userId: string;
  username: string;
  displayName: string;
  role: TripMemberRole;
};

export type InviteTripMemberInput = {
  username: string;
  role?: Exclude<TripMemberRole, 'owner' | 'admin'>;
};

export function getTripMembers(tripId: string) {
  return request<{ members: TripMember[] }>(`/trips/${tripId}/members`);
}

export function inviteTripMember(tripId: string, input: InviteTripMemberInput) {
  return request<{ members: TripMember[] }>(`/trips/${tripId}/members`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateTripMemberRole(
  tripId: string,
  memberId: string,
  role: Exclude<TripMemberRole, 'owner'>,
) {
  return request<{ members: TripMember[] }>(`/trips/${tripId}/members/${memberId}/role`, {
    method: 'PATCH',
    body: JSON.stringify({ role }),
  });
}

export function removeTripMember(tripId: string, memberId: string) {
  return request<{ members: TripMember[] }>(`/trips/${tripId}/members/${memberId}`, {
    method: 'DELETE',
  });
}
