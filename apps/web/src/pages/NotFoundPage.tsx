import { Compass, House } from 'lucide-react';
import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <main className="app-page not-found-page">
      <section className="not-found-panel">
        <span className="not-found-icon" aria-hidden="true">
          <Compass size={34} strokeWidth={1.8} />
        </span>
        <p className="eyebrow">页面不存在</p>
        <h1>这段路线暂时无法到达</h1>
        <p>链接可能已经失效，或页面地址有误。</p>
        <Link className="button-link" to="/">
          <House size={17} />
          返回首页
        </Link>
      </section>
    </main>
  );
}
