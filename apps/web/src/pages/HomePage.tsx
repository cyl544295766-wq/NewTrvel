import { Link } from 'react-router-dom';
import { useCurrentUser } from '../features/auth';
import { RecentExpenseList } from '../features/dashboard/components/RecentExpenseList';
import { RecentTripList } from '../features/dashboard/components/RecentTripList';
import { StatCard } from '../features/dashboard/components/StatCard';
import { UpcomingTripList } from '../features/dashboard/components/UpcomingTripList';
import { useDashboard } from '../features/dashboard/hooks/useDashboard';

export function HomePage() {
  const currentUser = useCurrentUser();
  const dashboard = useDashboard();
  const displayName = currentUser.data?.user.displayName ?? '旅行者';
  const data = dashboard.data;
  const firstTripId = data?.recentTrips[0]?.id ?? data?.upcomingTrips[0]?.id;

  if (dashboard.isLoading) {
    return <main className="loading-shell">首页数据加载中...</main>;
  }

  if (dashboard.isError || !data) {
    return <main className="loading-shell">首页数据加载失败，请稍后重试</main>;
  }

  return (
    <main className="app-page dashboard-page">
      <header className="top-bar dashboard-hero">
        <div>
          <p className="eyebrow">首页</p>
          <h1>欢迎回来，{displayName}</h1>
        </div>
        <div className="top-actions dashboard-actions">
          <Link className="button-link" to="/trips/new">
            新建旅行
          </Link>
          {firstTripId ? (
            <Link className="secondary-button" to={`/trips/${firstTripId}/expenses`}>
              查看费用
            </Link>
          ) : (
            <button className="secondary-button" disabled type="button">
              查看费用
            </button>
          )}
          <button className="secondary-button" disabled type="button">
            查看地图
          </button>
        </div>
      </header>

      <section className="dashboard-stats-grid" aria-label="数据统计">
        <StatCard label="旅行次数" value={`${data.stats.tripCount} 次`} />
        <StatCard label="总花费" value={`人民币 ${data.stats.totalExpenseAmount}`} />
        <StatCard label="总天数" value={`${data.stats.totalDays} 天`} />
      </section>

      <section className="dashboard-grid">
        <RecentTripList trips={data.recentTrips} />
        <UpcomingTripList trips={data.upcomingTrips} />
        <RecentExpenseList expenses={data.recentExpenses} />
        <section className="content-panel dashboard-card">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">照片</p>
              <h2>最近照片</h2>
            </div>
          </div>
          <div className="photo-placeholder-grid" aria-label="最近照片占位">
            {Array.from({ length: 6 }, (_, index) => (
              <div aria-hidden="true" className="photo-placeholder" key={index} />
            ))}
          </div>
          <p className="empty-state">照片模块尚未启用</p>
        </section>
      </section>
    </main>
  );
}
