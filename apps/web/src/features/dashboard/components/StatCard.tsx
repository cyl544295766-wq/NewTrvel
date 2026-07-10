import { LucideIcon } from 'lucide-react';

type StatCardProps = {
  icon: LucideIcon;
  label: string;
  value: string;
};

export function StatCard({ icon: Icon, label, value }: StatCardProps) {
  return (
    <article className="dashboard-stat-card">
      <div className="dashboard-stat-heading">
        <span>{label}</span>
        <Icon aria-hidden="true" size={18} strokeWidth={2} />
      </div>
      <strong>{value}</strong>
    </article>
  );
}
