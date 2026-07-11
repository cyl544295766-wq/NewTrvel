import { FormEvent, useState } from 'react';
import { ArrowRight, Eye, EyeOff, LoaderCircle, LockKeyhole, UserRound } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useLogin } from '../hooks/useAuth';

export function LoginForm() {
  const navigate = useNavigate();
  const loginMutation = useLogin();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    await loginMutation.mutateAsync({ username, password });
    navigate('/', { replace: true });
  }

  return (
    <form className="auth-form" onSubmit={handleSubmit}>
      <label>
        <span>用户名</span>
        <span className="auth-input-shell">
          <UserRound aria-hidden="true" size={18} strokeWidth={1.8} />
          <input
            autoComplete="username"
            autoFocus
            minLength={3}
            name="username"
            onChange={(event) => setUsername(event.target.value)}
            pattern="[a-zA-Z0-9_-]{3,32}"
            placeholder="请输入用户名"
            required
            type="text"
            value={username}
          />
        </span>
      </label>
      <label>
        <span>密码</span>
        <span className="auth-input-shell">
          <LockKeyhole aria-hidden="true" size={18} strokeWidth={1.8} />
          <input
            autoComplete="current-password"
            minLength={8}
            name="password"
            onChange={(event) => setPassword(event.target.value)}
            placeholder="请输入密码"
            required
            type={showPassword ? 'text' : 'password'}
            value={password}
          />
          <button
            aria-label={showPassword ? '隐藏密码' : '显示密码'}
            className="password-toggle"
            onClick={() => setShowPassword((visible) => !visible)}
            title={showPassword ? '隐藏密码' : '显示密码'}
            type="button"
          >
            {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
          </button>
        </span>
      </label>
      {loginMutation.isError ? (
        <p aria-live="polite" className="form-error">用户名或密码错误。</p>
      ) : null}
      <button className="login-submit" disabled={loginMutation.isPending} type="submit">
        <span>{loginMutation.isPending ? '登录中...' : '进入工作台'}</span>
        {loginMutation.isPending ? (
          <LoaderCircle aria-hidden="true" className="spin-icon" size={18} />
        ) : (
          <ArrowRight aria-hidden="true" size={18} />
        )}
      </button>
    </form>
  );
}
