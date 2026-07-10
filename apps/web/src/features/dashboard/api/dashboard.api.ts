import { request } from '../../../services/http';
import { DashboardData } from '../types/dashboard.types';

export function getDashboard(): Promise<DashboardData> {
  return request<DashboardData>('/dashboard');
}
