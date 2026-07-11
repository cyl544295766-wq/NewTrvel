import {
  CalendarDays,
  CalendarRange,
  MapPin,
  Plane,
  ReceiptText,
  Route,
  TrendingUp,
  WalletCards,
} from 'lucide-react';
import { StatsSummary } from '../types/travel-stats.types';

type StatGridProps = { stats: StatsSummary };

export function StatGrid({ stats }: StatGridProps) {
  const items = [
    { label: '旅行次数', value: `${stats.tripCount} 次`, icon: Plane },
    { label: '旅行天数', value: `${stats.totalDays} 天`, icon: CalendarDays },
    {
      label: '总花费',
      value: formatMoney(stats.primaryCurrency, stats.totalExpenseAmount),
      icon: WalletCards,
    },
    { label: '目的地', value: `${stats.destinationCount} 个`, icon: MapPin },
    { label: '平均行程', value: `${stats.averageTripDays} 天`, icon: Route },
    {
      label: '平均花费',
      value: formatMoney(stats.primaryCurrency, stats.averageExpensePerTrip),
      icon: ReceiptText,
    },
    {
      label: '最常前往',
      value: stats.mostVisitedDestination.label ?? '暂无数据',
      detail: stats.mostVisitedDestination.count
        ? `${stats.mostVisitedDestination.count} 次`
        : undefined,
      icon: TrendingUp,
    },
    {
      label: '高频月份',
      value: stats.busiestMonth.label ?? '暂无数据',
      detail: stats.busiestMonth.count ? `${stats.busiestMonth.count} 次旅行` : undefined,
      icon: CalendarRange,
    },
  ];

  return (
    <section className="travel-stat-grid" aria-label="旅行统计">
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <article className="travel-stat-card" key={item.label}>
            <div>
              <span>{item.label}</span>
              <Icon aria-hidden="true" size={18} />
            </div>
            <strong>{item.value}</strong>
            {item.detail ? <small>{item.detail}</small> : null}
          </article>
        );
      })}
    </section>
  );
}

function formatMoney(currency: string, amount: string) {
  return `${currency === 'CNY' ? '¥' : currency} ${amount}`;
}
