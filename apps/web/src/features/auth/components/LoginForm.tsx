import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLogin } from '../hooks/useAuth';

export function LoginForm() {
  const navigate = useNavigate();
  const loginMutation = useLogin();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    await loginMutation.mutateAsync({ username, password });
    navigate('/', { replace: true });
  }

  return (
    <form className="auth-form" onSubmit={handleSubmit}>
      <label>
        <span>用户名</span>
        <input
          autoComplete="username"
          minLength={3}
          name="username"
          onChange={(event) => setUsername(event.target.value)}
          pattern="[a-zA-Z0-9_-]{3,32}"
          required
          type="text"
          value={username}
        />
      </label>
      <label>
        <span>密码</span>
        <input
          autoComplete="current-password"
          minLength={8}
          name="password"
          onChange={(event) => setPassword(event.target.value)}
          required
          type="password"
          value={password}
        />
      </label>
      {loginMutation.isError ? <p className="form-error">用户名或密码错误。</p> : null}
      <button disabled={loginMutation.isPending} type="submit">
        {loginMutation.isPending ? '登录中...' : '登录'}
      </button>
    </form>
  );
}
