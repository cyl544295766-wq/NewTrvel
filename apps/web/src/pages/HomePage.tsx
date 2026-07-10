import { CalendarRange, Luggage, MapPinned, Plus, WalletCards } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useCurrentUser } from '../features/auth';
import { RecentExpenseList } from '../features/dashboard/components/RecentExpenseList';
import { RecentPhotoList } from '../features/dashboard/components/RecentPhotoList';
import { RecentJournalList } from '../features/dashboard/components/RecentJournalList';
import { RecentTripList } from '../features/dashboard/components/RecentTripList';
import { FeaturedTrip } from '../features/dashboard/components/FeaturedTrip';
import { StatCard } from '../features/dashboard/components/StatCard';
import { UpcomingDocumentList } from '../features/dashboard/components/UpcomingDocumentList';
import { useDashboard } from '../features/dashboard/hooks/useDashboard';

export function HomePage() {
  const currentUser = useCurrentUser();
  const dashboard = useDashboard();
  const displayName = currentUser.data?.user.displayName ?? '旅行者';
  const data = dashboard.data;

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
          <p className="eyebrow">{formatToday()}</p>
          <h1>欢迎回来，{displayName}</h1>
          <p className="dashboard-intro">查看下一段行程和最近更新。</p>
        </div>
        <div className="top-actions dashboard-actions">
          <Link className="button-link" to="/trips/new">
            <Plus size={17} />
            新建旅行
          </Link>
        </div>
      </header>

      <section className="dashboard-overview-grid">
        <FeaturedTrip trip={data.upcomingTrips[0] ?? data.recentTrips[0]} />
        <div className="dashboard-stats-grid home-stats-grid" aria-label="数据统计">
          <StatCard icon={MapPinned} label="旅行次数" value={`${data.stats.tripCount} 次`} />
          <StatCard icon={WalletCards} label="总花费" value={`¥ ${data.stats.totalExpenseAmount}`} />
          <StatCard icon={CalendarRange} label="旅行天数" value={`${data.stats.totalDays} 天`} />
          <StatCard icon={Luggage} label="待打包" value={`${data.stats.pendingPackingItemCount} 件`} />
        </div>
      </section>

      <div className="dashboard-section-heading">
        <div>
          <p className="eyebrow">近期动态</p>
          <h2>继续整理你的旅行</h2>
        </div>
      </div>

      <section className="dashboard-grid">
        <RecentTripList trips={data.recentTrips} />
        <RecentExpenseList expenses={data.recentExpenses} />
        <RecentPhotoList photos={data.recentPhotos} />
        <UpcomingDocumentList documents={data.upcomingDocuments} />
        <RecentJournalList journals={data.recentJournals} />
      </section>
    </main>
  );
}

function formatToday() {
  return new Date().toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  });
}
