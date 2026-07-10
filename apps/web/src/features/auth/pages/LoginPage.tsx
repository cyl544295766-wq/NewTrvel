import { Navigate } from 'react-router-dom';
import { LoginForm } from '../components/LoginForm';
import { useCurrentUser } from '../hooks/useAuth';

export function LoginPage() {
  const currentUser = useCurrentUser();

  if (currentUser.isSuccess) {
    return <Navigate replace to="/" />;
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-label="Travel OS sign in">
        <p className="eyebrow">Travel OS</p>
        <h1>登录</h1>
        <p className="login-copy">进入你的私人旅行工作台。</p>
        <LoginForm />
      </section>
    </main>
  );
}
