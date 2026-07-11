import { LucideIcon } from 'lucide-react';

type StatCardProps = {
  hint?: string;
  icon: LucideIcon;
  label: string;
  value: string;
  variant?: 'wide';
};

export function StatCard({ hint, icon: Icon, label, value, variant }: StatCardProps) {
  return (
    <article className={`dashboard-stat-card${variant ? ` dashboard-stat-card-${variant}` : ''}`}>
      <div className="dashboard-stat-heading">
        <span className="dashboard-stat-icon" aria-hidden="true">
          <Icon size={21} strokeWidth={1.8} />
        </span>
      </div>
      <div className="dashboard-stat-value">
        <strong>{value}</strong>
        <span>{label}</span>
        {hint ? <small>{hint}</small> : null}
      </div>
    </article>
  );
}
