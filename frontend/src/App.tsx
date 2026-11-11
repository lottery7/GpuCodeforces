import { Component, lazy, Suspense, useEffect, useRef, type ReactNode } from 'react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { useSession } from './auth';
import { AuthDialogProvider, AuthLanding, useAuthDialog } from './auth-dialog';
import { ErrorMessage, PageHeader } from './ui';
import { userPath } from './utils';
const Problems = lazy(() => import('./pages/Problems'));
const Problem = lazy(() => import('./pages/Problem'));
const Submissions = lazy(() => import('./pages/Submissions'));
const Submission = lazy(() => import('./pages/Submission'));
const Contests = lazy(() => import('./pages/Contests'));
const Contest = lazy(() => import('./pages/Contest'));
const ContestEditor = lazy(() => import('./pages/ContestEditor'));
const Profile = lazy(() => import('./pages/Profile'));
const Rating = lazy(() => import('./pages/Rating'));
const Home = lazy(() => import('./pages/Home'));

const navIcons: Record<string, ReactNode> = {
  '/problems': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 3h6v4H9z" /><path d="M4 7h16v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" /><path d="M9 12h6M9 16h6" /></svg>,
  '/contests': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6" /><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18" /><path d="M4 22h16" /><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22" /><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22" /><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z" /></svg>,
  '/submissions': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 2 11 13" /><path d="M22 2 15 22l-4-9-9-4Z" /></svg>,
  '/rating': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3v18h18" /><path d="M7 15l3-3 3 3 4-6" /></svg>,
};

const navigation = [
  { to: '/problems', title: 'Задачи', icon: navIcons['/problems'] },
  { to: '/contests', title: 'Соревнования', icon: navIcons['/contests'] },
  { to: '/submissions', title: 'Посылки', icon: navIcons['/submissions'] },
  { to: '/rating', title: 'Рейтинг', icon: navIcons['/rating'] },
];

class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <><PageHeader title="Не удалось открыть страницу" /><p>Обновите страницу и попробуйте снова.</p><button className="btn secondary" onClick={() => window.location.reload()}>Обновить</button></>;
    return this.props.children;
  }
}

function Shell() {
  const session = useSession();
  const location = useLocation();
  const openAuth = useAuthDialog();
  const menu = useRef<HTMLDetailsElement>(null);
  const main = useRef<HTMLElement>(null);
  const previousPath = useRef(location.pathname);
  useEffect(() => {
    if (menu.current) menu.current.open = false;
    if (previousPath.current !== location.pathname) {
      window.scrollTo(0, 0);
      main.current?.focus({ preventScroll: true });
      previousPath.current = location.pathname;
    }
  }, [location.pathname, location.key]);
  const returnTo = () => location.pathname + location.search;
  function links() {
    return navigation.map(({ to, title, icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive || (to === '/contests' && location.pathname.startsWith('/contest/')) || (to === '/problems' && location.pathname.startsWith('/problem/')) || (to === '/submissions' && location.pathname.startsWith('/submission/')) ? 'active' : undefined}><span className="nav-icon" aria-hidden="true">{icon}</span><span>{title}</span></NavLink>);
  }
  function account() {
    if (session.isLoading) return <span className="muted" role="status">Загрузка...</span>;
    return session.user ? <Link to={userPath(session.user.id)}>{session.user.handle}</Link> : <button className="text-link" onClick={() => openAuth('login', returnTo())}>Войти</button>;
  }
  return <>
    <a className="skip-link" href="#content">К содержимому</a>
    <header className="site-header"><div className="header-inner">
      <Link className="brand" to="/">GPU Forces</Link>
      <nav className="main-nav" aria-label="Основная навигация">{links()}</nav>
      <div className="account">{account()}</div>
      <details ref={menu} className="mobile-nav"><summary>Меню</summary><nav aria-label="Мобильная навигация">{links()}{account()}</nav></details>
    </div></header>
    <main id="content" ref={main} tabIndex={-1} className="container">
      <div className="global-feedback">
      {Boolean(session.error) && <div className="session-error"><ErrorMessage error={session.error} /><button className="text-link" onClick={session.refresh}>Проверить соединение</button></div>}
      </div>
      <PageBoundary key={location.pathname}>
        <Suspense fallback={<div className="empty" role="status">Загрузка...</div>}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/problem/:id" element={<Problem />} />
          <Route path="/submissions" element={<Submissions />} />
          <Route path="/submission/:id" element={<Submission />} />
          <Route path="/contests" element={<Contests />} />
          <Route path="/contests/new" element={<ContestEditor />} />
          <Route path="/contest/:id" element={<Contest />} />
          <Route path="/contest/:id/edit" element={<ContestEditor />} />
          <Route path="/login" element={<><Problems /><AuthLanding mode="login" /></>} />
          <Route path="/register" element={<><Problems /><AuthLanding mode="register" /></>} />
          <Route path="/rating" element={<Rating />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="*" element={<><PageHeader title="Страница не найдена" /><Link to="/problems">Перейти к задачам</Link></>} />
        </Routes>
        </Suspense>
      </PageBoundary>
    </main>
  </>;
}

export default function App() {
  return <AuthDialogProvider><Shell /></AuthDialogProvider>;
}