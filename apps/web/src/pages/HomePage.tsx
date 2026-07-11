import {
  ArrowUpRight,
  BellRing,
  CalendarRange,
  ChartNoAxesCombined,
  Luggage,
  MapPinned,
  Plus,
  WalletCards,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { useCurrentUser } from '../features/auth';
import { RecentExpenseList } from '../features/dashboard/components/RecentExpenseList';
import { RecentPhotoList } from '../features/dashboard/components/RecentPhotoList';
import { RecentJournalList } from '../features/dashboard/components/RecentJournalList';
import { RecentTripList } from '../features/dashboard/components/RecentTripList';
import { FeaturedTrip } from '../features/dashboard/components/FeaturedTrip';
import { StatCard } from '../features/dashboard/components/StatCard';
import { UpcomingDocumentList } from '../features/dashboard/components/UpcomingDocumentList';
import { UpcomingTripList } from '../features/dashboard/components/UpcomingTripList';
import { useDashboard } from '../features/dashboard/hooks/useDashboard';
import { DashboardTrip } from '../features/dashboard/types/dashboard.types';

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

  const featuredTrip = data.upcomingTrips[0] ?? data.recentTrips[0];
  const featuredPhoto = data.recentPhotos.find((photo) => photo.tripId === featuredTrip?.id);

  return (
    <main className="app-page dashboard-page">
      <header className="dashboard-editorial-hero">
        <div className="dashboard-hero-main">
          <FeaturedTrip photo={featuredPhoto} trip={featuredTrip} />
          <section className="dashboard-welcome-panel">
            <div>
              <p className="eyebrow">{formatToday()}</p>
              <h1>欢迎回来，<br />{displayName}</h1>
              <p className="dashboard-intro">{getTravelStatus(featuredTrip)}</p>
            </div>

            <div className="dashboard-welcome-actions">
              {featuredTrip ? (
                <Link className="button-link" to={`/trips/${featuredTrip.id}`}>
                  查看行程
                  <ArrowUpRight size={17} />
                </Link>
              ) : (
                <Link className="button-link" to="/trips/new">
                  开始规划
                  <Plus size={17} />
                </Link>
              )}
              <Link className="secondary-button" to="/stats">
                <ChartNoAxesCombined size={17} />
                旅行统计
              </Link>
            </div>

            <p className="dashboard-year-summary">
              已记录 <strong>{data.stats.tripCount}</strong> 次旅行
              <span aria-hidden="true">·</span>
              累计 <strong>{data.stats.totalDays}</strong> 天
            </p>
          </section>
        </div>
        <RecentTripList photos={data.recentPhotos} trips={data.recentTrips} />
      </header>

      <section className="dashboard-data-section">
        <div className="dashboard-section-heading">
          <div>
            <p className="eyebrow">旅行概况</p>
            <h2>你的足迹，一目了然</h2>
          </div>
          <Link className="dashboard-heading-link" to="/stats">查看完整统计 <ArrowUpRight size={15} /></Link>
        </div>

        <div className="dashboard-stats-grid home-stats-grid" aria-label="数据统计">
          <StatCard
            hint="跨全部旅行累计"
            icon={WalletCards}
            label="总花费"
            value={`¥ ${data.stats.totalExpenseAmount}`}
            variant="wide"
          />
          <StatCard icon={MapPinned} label="旅行次数" value={`${data.stats.tripCount} 次`} />
          <StatCard icon={CalendarRange} label="旅行天数" value={`${data.stats.totalDays} 天`} />
          <StatCard
            icon={Luggage}
            label="待打包"
            value={data.stats.pendingPackingItemCount === 0 ? '准备就绪' : `${data.stats.pendingPackingItemCount} 件`}
          />
        </div>
      </section>

      {data.stats.unreadNotificationCount > 0 ? (
        <Link className="dashboard-notification-card" to="/notifications">
          <span aria-hidden="true">
            <BellRing size={20} />
          </span>
          <div>
            <strong>你有 {data.stats.unreadNotificationCount} 条未读通知</strong>
            <p>查看行程、文档和打包提醒</p>
          </div>
          <b>查看通知</b>
        </Link>
      ) : null}

      <div className="dashboard-section-heading dashboard-activity-heading">
        <div>
          <p className="eyebrow">近期动态</p>
          <h2>继续整理你的旅行</h2>
        </div>
        <p>最近更新与待办事项</p>
      </div>

      <section className="dashboard-editorial-grid">
        <RecentJournalList journals={data.recentJournals} photos={data.recentPhotos} />
        <div className="dashboard-side-stack">
          <RecentPhotoList photos={data.recentPhotos} />
          <RecentExpenseList expenses={data.recentExpenses.slice(0, 3)} />
          <UpcomingDocumentList documents={data.upcomingDocuments} />
        </div>
      </section>

      <section className="dashboard-upcoming-section">
        <div className="dashboard-section-heading">
          <div>
            <p className="eyebrow">即将开始</p>
            <h2>下一段旅程正在靠近</h2>
          </div>
          <Link className="dashboard-heading-link" to="/trips/new">规划新旅行 <Plus size={15} /></Link>
        </div>
        <UpcomingTripList trips={data.upcomingTrips} />
      </section>
    </main>
  );
}

function getTravelStatus(trip: DashboardTrip | undefined) {
  if (!trip) return '建立你的第一段旅行，把灵感变成清晰的计划。';
  if (!trip.startDate) return `${trip.title} 正在等待确定出发日期。`;

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const startDate = new Date(trip.startDate);
  startDate.setHours(0, 0, 0, 0);
  const days = Math.round((startDate.getTime() - today.getTime()) / 86_400_000);

  if (days > 0) return `你的${trip.destination || trip.title}之旅将在 ${days} 天后开始。`;
  if (days === 0) return `今天出发，${trip.title}已经开始。`;
  return `${trip.title}正在进行，继续记录沿途时刻。`;
}

function formatToday() {
  return new Date().toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  });
}
