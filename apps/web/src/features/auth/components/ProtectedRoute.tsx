import { Navigate } from 'react-router-dom';
import { AppShell } from '../../../components/AppShell';
import { useCurrentUser } from '../hooks/useAuth';

type ProtectedRouteProps = {
  children: React.ReactNode;
};

export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const currentUser = useCurrentUser();

  if (currentUser.isLoading) {
    return <main className="loading-shell">Loading...</main>;
  }

  if (currentUser.isError) {
    return <Navigate replace to="/login" />;
  }

  return <AppShell>{children}</AppShell>;
}
