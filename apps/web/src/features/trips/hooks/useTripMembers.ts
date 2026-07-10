import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  InviteTripMemberInput,
  TripMemberRole,
  getTripMembers,
  inviteTripMember,
  removeTripMember,
  updateTripMemberRole,
} from '../api/members.api';

export const tripMembersQueryKey = (tripId: string) => ['trip-members', tripId];

export function useTripMembers(tripId: string) {
  return useQuery({
    queryKey: tripMembersQueryKey(tripId),
    queryFn: () => getTripMembers(tripId),
    enabled: Boolean(tripId),
  });
}

export function useInviteTripMember(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: InviteTripMemberInput) => inviteTripMember(tripId, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripMembersQueryKey(tripId) }),
  });
}

export function useUpdateTripMemberRole(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      memberId,
      role,
    }: {
      memberId: string;
      role: Exclude<TripMemberRole, 'owner'>;
    }) => updateTripMemberRole(tripId, memberId, role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripMembersQueryKey(tripId) }),
  });
}

export function useRemoveTripMember(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (memberId: string) => removeTripMember(tripId, memberId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tripMembersQueryKey(tripId) }),
  });
}
