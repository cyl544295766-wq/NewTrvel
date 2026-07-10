import {
  CalendarDays,
  Camera,
  FileText,
  House,
  LogOut,
  Map,
  NotebookPen,
  PackageCheck,
  Plus,
  ReceiptText,
  Route,
} from 'lucide-react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useCurrentUser, useLogout } from '../features/auth';
import { NotificationBell } from '../features/notifications';

type AppShellProps = {
  children: React.ReactNode;
};

export function AppShell({ children }: AppShellProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const currentUser = useCurrentUser();
  const logout = useLogout();
  const displayName = currentUser.data?.user.displayName ?? '旅行者';
  const tripId = getTripId(location.pathname);

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <header className="app-header">
        <div className="app-header-inner">
          <Link aria-label="Travel OS 首页" className="app-brand" to="/">
            <span className="app-brand-mark" aria-hidden="true">
              <Route size={20} strokeWidth={2.2} />
            </span>
            <span>
              <strong>Travel OS</strong>
              <small>旅行工作台</small>
            </span>
          </Link>

          <nav className="app-nav" aria-label="主导航">
            <NavLink className={({ isActive }) => (isActive ? 'active' : undefined)} end to="/">
              <House size={17} />
              <span>首页</span>
            </NavLink>
            <NavLink
              className={({ isActive }) => (isActive ? 'active' : undefined)}
              to="/trips/new"
            >
              <Plus size={17} />
              <span>新建旅行</span>
            </NavLink>
          </nav>

          <div className="app-account">
            <NotificationBell />
            <span className="app-avatar" aria-hidden="true">
              {displayName.slice(0, 1).toUpperCase()}
            </span>
            <span className="app-account-name">{displayName}</span>
            <button
              aria-label="退出登录"
              className="header-icon-button"
              disabled={logout.isPending}
              onClick={() => {
                logout.mutate(undefined, {
                  onSuccess: () => {
                    navigate('/login', { replace: true });
                  },
                });
              }}
              title="退出登录"
              type="button"
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </header>

      {tripId ? <TripWorkspaceNav tripId={tripId} /> : null}

      <div id="main-content">{children}</div>
    </div>
  );
}

const workspaceItems = [
  { label: '概览', path: '', icon: House },
  { label: '日程', path: '/itinerary', icon: CalendarDays },
  { label: '费用', path: '/expenses', icon: ReceiptText },
  { label: '照片', path: '/photos', icon: Camera },
  { label: '文档', path: '/documents', icon: FileText },
  { label: '打包', path: '/packing-lists', icon: PackageCheck },
  { label: '游记', path: '/journals', icon: NotebookPen },
  { label: '地图', path: '/map', icon: Map },
];

function TripWorkspaceNav({ tripId }: { tripId: string }) {
  const basePath = `/trips/${tripId}`;

  return (
    <div className="workspace-nav-shell">
      <nav className="workspace-nav" aria-label="行程工作区">
        <span className="workspace-nav-label">行程工作区</span>
        {workspaceItems.map((item) => {
          const Icon = item.icon;
          const to = `${basePath}${item.path}`;

          return (
            <NavLink
              className={({ isActive }) => (isActive ? 'active' : undefined)}
              end={item.path === ''}
              key={item.path}
              to={to}
            >
              <Icon size={16} strokeWidth={2} />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>
    </div>
  );
}

function getTripId(pathname: string) {
  const match = pathname.match(/^\/trips\/([^/]+)/);
  const tripId = match?.[1];
  return tripId && tripId !== 'new' ? tripId : null;
}
