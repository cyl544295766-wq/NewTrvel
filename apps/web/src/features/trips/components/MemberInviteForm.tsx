import { FormEvent, useState } from 'react';
import { InviteTripMemberInput } from '../api/members.api';

type Props = {
  isSubmitting: boolean;
  onSubmit: (input: InviteTripMemberInput) => Promise<void>;
};

export function MemberInviteForm({ isSubmitting, onSubmit }: Props) {
  const [username, setUsername] = useState('');
  const [role, setRole] = useState<InviteTripMemberInput['role']>('member');
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');

    try {
      await onSubmit({ username: username.trim(), role });
      setUsername('');
      setRole('member');
    } catch {
      setError('邀请成员失败，请确认用户名有效且未加入旅行');
    }
  }

  return (
    <form className="compact-form member-invite-form" onSubmit={handleSubmit}>
      <label>
        <span>用户名</span>
        <input
          placeholder="输入用户名"
          required
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
      </label>
      <label>
        <span>角色</span>
        <select value={role} onChange={(event) => setRole(event.target.value as typeof role)}>
          <option value="member">成员</option>
          <option value="viewer">观察者</option>
        </select>
      </label>
      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? '邀请中...' : '邀请成员'}
      </button>
      {error ? <p className="form-error">{error}</p> : null}
    </form>
  );
}
