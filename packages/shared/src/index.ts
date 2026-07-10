export const APP_NAME = 'Travel OS';

export type ApiStatus = 'ok' | 'error';

export type ApiResponse<TData> = {
  status: ApiStatus;
  data: TData;
};
