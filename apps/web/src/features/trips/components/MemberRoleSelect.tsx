import { TripMemberRole } from '../api/members.api';

type Props = {
  disabled: boolean;
  role: TripMemberRole;
  onChange: (role: Exclude<TripMemberRole, 'owner'>) => void;
};

export const roleLabels: Record<TripMemberRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  viewer: '观察者',
};

export function MemberRoleSelect({ disabled, role, onChange }: Props) {
  if (role === 'owner') {
    return <span className="status-badge">{roleLabels.owner}</span>;
  }

  return (
    <select
      aria-label="修改角色"
      disabled={disabled}
      value={role}
      onChange={(event) => onChange(event.target.value as Exclude<TripMemberRole, 'owner'>)}
    >
      <option value="admin">管理员</option>
      <option value="member">成员</option>
      <option value="viewer">观察者</option>
    </select>
  );
}
