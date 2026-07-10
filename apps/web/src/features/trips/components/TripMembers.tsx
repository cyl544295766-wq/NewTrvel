import { TripMemberRole } from '../api/members.api';
import {
  useInviteTripMember,
  useRemoveTripMember,
  useTripMembers,
  useUpdateTripMemberRole,
} from '../hooks/useTripMembers';
import { MemberInviteForm } from './MemberInviteForm';
import { MemberRoleSelect, roleLabels } from './MemberRoleSelect';

type Props = {
  currentUserRole: TripMemberRole;
  tripId: string;
};

export function TripMembers({ currentUserRole, tripId }: Props) {
  const members = useTripMembers(tripId);
  const inviteMember = useInviteTripMember(tripId);
  const updateRole = useUpdateTripMemberRole(tripId);
  const removeMember = useRemoveTripMember(tripId);
  const canManage = currentUserRole === 'owner' || currentUserRole === 'admin';

  return (
    <section className="members-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">成员</p>
          <h2>成员管理</h2>
        </div>
      </div>

      {canManage ? (
        <MemberInviteForm
          isSubmitting={inviteMember.isPending}
          onSubmit={async (input) => {
            await inviteMember.mutateAsync(input);
          }}
        />
      ) : null}

      {members.data?.members.length === 0 ? <p className="empty-state">暂无成员</p> : null}

      <div className="member-list">
        {(members.data?.members ?? []).map((member) => (
          <article className="member-item" key={member.id}>
            <div>
              <strong>{member.displayName}</strong>
              <p>{member.username}</p>
            </div>
            {canManage ? (
              <div className="member-actions">
                <span>修改角色</span>
                <MemberRoleSelect
                  disabled={updateRole.isPending || member.role === 'owner'}
                  role={member.role}
                  onChange={(role) => {
                    void updateRole.mutateAsync({ memberId: member.id, role });
                  }}
                />
                <button
                  className="secondary-button danger-button"
                  disabled={removeMember.isPending || member.role === 'owner'}
                  onClick={() => {
                    void removeMember.mutateAsync(member.id);
                  }}
                  type="button"
                >
                  移除成员
                </button>
              </div>
            ) : (
              <span className="status-badge">{roleLabels[member.role]}</span>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
