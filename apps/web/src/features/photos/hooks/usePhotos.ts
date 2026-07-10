import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { dashboardQueryKey } from '../../dashboard/hooks/useDashboard';
import { tripQueryKey, tripsQueryKey } from '../../trips/hooks/queryKeys';
import { createPhoto, deletePhoto, getPhotos, updatePhoto } from '../api/photos.api';
import { PhotoInput, PhotoUpdateInput } from '../types/photo.types';

const photosKey = (tripId: string) => ['photos', tripId];

function invalidatePhotoDependencies(
  queryClient: ReturnType<typeof useQueryClient>,
  tripId: string,
) {
  queryClient.invalidateQueries({ queryKey: photosKey(tripId) });
  queryClient.invalidateQueries({ queryKey: dashboardQueryKey });
  queryClient.invalidateQueries({ queryKey: tripsQueryKey });
  queryClient.invalidateQueries({ queryKey: tripQueryKey(tripId) });
}

export function usePhotos(tripId: string) {
  return useQuery({
    queryKey: photosKey(tripId),
    queryFn: () => getPhotos(tripId),
    enabled: Boolean(tripId),
  });
}

export function useCreatePhoto(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: PhotoInput) => createPhoto(tripId, input),
    onSuccess: () => invalidatePhotoDependencies(queryClient, tripId),
  });
}

export function useUpdatePhoto(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ photoId, input }: { photoId: string; input: PhotoUpdateInput }) =>
      updatePhoto(tripId, photoId, input),
    onSuccess: () => invalidatePhotoDependencies(queryClient, tripId),
  });
}

export function useDeletePhoto(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (photoId: string) => deletePhoto(tripId, photoId),
    onSuccess: () => invalidatePhotoDependencies(queryClient, tripId),
  });
}
