import { Compass, MapPinned } from 'lucide-react';
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
      <section className="login-visual" aria-label="Travel OS 旅行工作台">
        <div className="login-brand">
          <span className="login-brand-mark" aria-hidden="true">
            <Compass size={22} strokeWidth={1.8} />
          </span>
          <span>
            <strong>Travel OS</strong>
            <small>旅行工作台</small>
          </span>
        </div>

        <div className="login-visual-copy">
          <MapPinned aria-hidden="true" size={24} strokeWidth={1.7} />
          <p>计划清晰，出发从容</p>
          <h1>下一段旅程，<br />从这里开始。</h1>
        </div>

        <p className="login-image-credit">TRAVEL WITH INTENTION</p>
      </section>

      <section className="login-workspace" aria-label="登录 Travel OS">
        <div className="login-panel">
          <div className="login-panel-heading">
            <p className="eyebrow">WELCOME BACK</p>
            <h2>欢迎回来</h2>
            <p className="login-copy">登录后继续整理你的旅行。</p>
          </div>
          <LoginForm />
          <p className="login-footer">Travel OS · 你的私人旅行工作台</p>
        </div>
      </section>
    </main>
  );
}
