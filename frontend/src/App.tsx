import { Component, lazy, Suspense, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { useSession } from './auth';
import { ErrorMessage, PageHeader } from './ui';
import { userPath } from './utils';
const Problems = lazy(() => import('./pages/Problems'));
const Problem = lazy(() => import('./pages/Problem'));
const Submissions = lazy(() => import('./pages/Submissions'));
const Submission = lazy(() => import('./pages/Submission'));
const Contests = lazy(() => import('./pages/Contests'));
const Contest = lazy(() => import('./pages/Contest'));
const ContestEditor = lazy(() => import('./pages/ContestEditor'));
const Auth = lazy(() => import('./pages/Auth'));
const Profile = lazy(() => import('./pages/Profile'));
const Rating = lazy(() => import('./pages/Rating'));

const navigation = [
  ['/problems', 'Задачи'], ['/contests', 'Соревнования'],
  ['/submissions', 'Посылки'], ['/rating', 'Рейтинг'],
];

class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <><PageHeader title="Не удалось открыть страницу" /><p>Обновите страницу и попробуйте снова.</p><button className="btn secondary" onClick={() => window.location.reload()}>Обновить</button></>;
    return this.props.children;
  }
}

export default function App() {
  const session = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const menu = useRef<HTMLDetailsElement>(null);
  const main = useRef<HTMLElement>(null);
  const previousPath = useRef(location.pathname);
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  useEffect(() => {
    if (menu.current) menu.current.open = false;
    setLogoutError(null);
    if (previousPath.current !== location.pathname) {
      window.scrollTo(0, 0);
      main.current?.focus({ preventScroll: true });
      previousPath.current = location.pathname;
    }
  }, [location.pathname, location.key]);
  async function logout() {
    setLoggingOut(true);
    setLogoutError(null);
    try { await session.logout(); navigate('/problems'); }
    catch (error) { setLogoutError(error); }
    finally { setLoggingOut(false); }
  }
  function links() {
    return navigation.map(([to, title]) => <NavLink key={to} to={to} className={({ isActive }) => isActive || (to === '/contests' && location.pathname.startsWith('/contest/')) || (to === '/problems' && location.pathname.startsWith('/problem/')) || (to === '/submissions' && location.pathname.startsWith('/submission/')) ? 'active' : undefined}>{title}</NavLink>);
  }
  function account() {
    if (session.isLoading) return <span className="muted" role="status">Загрузка...</span>;
    if (!session.user && ['/login', '/register'].includes(location.pathname)) return null;
    return session.user ? <><Link to={userPath(session.user.id)}>{session.user.handle}</Link><button onClick={() => void logout()} disabled={loggingOut}>{loggingOut ? 'Выход...' : 'Выйти'}</button></> : <Link to={`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`}>Войти</Link>;
  }
  return <>
    <a className="skip-link" href="#content">К содержимому</a>
    <header className="site-header"><div className="header-inner">
      <Link className="brand" to="/problems">GPU Forces</Link>
      <nav className="main-nav" aria-label="Основная навигация">{links()}</nav>
      <div className="account">{account()}</div>
      <details ref={menu} className="mobile-nav"><summary>Меню</summary><nav aria-label="Мобильная навигация">{links()}{account()}</nav></details>
    </div></header>
    <main id="content" ref={main} tabIndex={-1} className="container">
      <div className="global-feedback">
      {Boolean(session.error) && <div className="session-error"><ErrorMessage error={session.error} /><button className="text-link" onClick={session.refresh}>Проверить соединение</button></div>}
      <ErrorMessage error={logoutError} reveal />
      </div>
      <PageBoundary key={location.pathname}>
        <Suspense fallback={<div className="empty" role="status">Загрузка...</div>}>
        <Routes>
          <Route path="/" element={<Navigate to="/problems" replace />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/problem/:id" element={<Problem />} />
          <Route path="/submissions" element={<Submissions />} />
          <Route path="/submission/:id" element={<Submission />} />
          <Route path="/contests" element={<Contests />} />
          <Route path="/contests/new" element={<ContestEditor />} />
          <Route path="/contest/:id" element={<Contest />} />
          <Route path="/contest/:id/edit" element={<ContestEditor />} />
          <Route path="/login" element={<Auth key="login" mode="login" />} />
          <Route path="/register" element={<Auth key="register" mode="register" />} />
          <Route path="/rating" element={<Rating />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="*" element={<><PageHeader title="Страница не найдена" /><Link to="/problems">Перейти к задачам</Link></>} />
        </Routes>
        </Suspense>
      </PageBoundary>
    </main>
  </>;
}
