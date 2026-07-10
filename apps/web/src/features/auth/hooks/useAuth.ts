import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getMe, login, logout } from '../api/auth.api';
import { LoginInput } from '../types/auth.types';

const currentUserQueryKey = ['auth', 'me'];

export function useCurrentUser() {
  return useQuery({
    queryKey: currentUserQueryKey,
    queryFn: getMe,
    retry: false,
  });
}

export function useLogin() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: LoginInput) => login(input),
    onSuccess: (data) => {
      queryClient.setQueryData(currentUserQueryKey, data);
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: currentUserQueryKey });
    },
  });
}
