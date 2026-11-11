import { Component, lazy, Suspense, useEffect, useRef, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from './api/client';
import { useSession } from './auth';
import { AuthDialogProvider, AuthLanding, useAuthDialog } from './auth-dialog';
import { ErrorMessage, PageHeader } from './ui';
import { userPath } from './utils';
import Notifications from './components/Notifications';
import RuntimeNotice from './components/RuntimeNotice';
import './duels.css';
const Problems = lazy(() => import('./pages/Problems'));
const Problem = lazy(() => import('./pages/Problem'));
const Submissions = lazy(() => import('./pages/Submissions'));
const Submission = lazy(() => import('./pages/Submission'));
const Contests = lazy(() => import('./pages/Contests'));
const Contest = lazy(() => import('./pages/Contest'));
const ContestEditor = lazy(() => import('./pages/ContestEditor'));
const Duels = lazy(() => import('./pages/Duels'));
const NewDuel = lazy(() => import('./pages/NewDuel'));
const Profile = lazy(() => import('./pages/Profile'));
const Rating = lazy(() => import('./pages/Rating'));
const Virtual = lazy(() => import('./pages/Virtual'));
const Home = lazy(() => import('./pages/Home'));

const navIcons: Record<string, ReactNode> = {
  '/problems': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 3h6v4H9z" /><path d="M4 7h16v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" /><path d="M9 12h6M9 16h6" /></svg>,
  '/contests': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6" /><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18" /><path d="M4 22h16" /><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22" /><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22" /><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z" /></svg>,
  '/duels': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14.5 17.5 3 6V3h3l11.5 11.5" /><path d="m13 19 6-6" /><path d="m16 16 4 4" /><path d="m19 21 2-2" /><path d="M14.5 6.5 18 3h3v3l-3.5 3.5" /><path d="m5 14 4 4" /><path d="m7 17-3 3" /><path d="m3 19 2 2" /></svg>,
  '/submissions': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 2 11 13" /><path d="M22 2 15 22l-4-9-9-4Z" /></svg>,
  '/rating': <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3v18h18" /><path d="M7 15l3-3 3 3 4-6" /></svg>,
};

const navigation = [
  { to: '/problems', title: 'Задачи', icon: navIcons['/problems'] },
  { to: '/contests', title: 'Соревнования', icon: navIcons['/contests'] },
  { to: '/duels', title: 'Дуэли', icon: navIcons['/duels'] },
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
  const context = location.pathname.startsWith('/problem/') ? new URLSearchParams(location.search).get('contest') : null;
  const contestId = /^\/contest\/([^/]+)/.exec(location.pathname)?.[1] ?? (context?.startsWith('upsolve:') ? context.slice(8) : context && context !== 'practice' && !context.startsWith('virtual:') ? context : undefined);
  const room = useQuery({ queryKey: ['contest', contestId, session.user?.id], queryFn: ({ signal }) => api.getContest(contestId ?? '', signal), enabled: Boolean(contestId) && !session.isLoading });
  const duelRoom = room.data?.format === 'duel';
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
    return navigation.map(({ to, title, icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive || (to === '/contests' && location.pathname.startsWith('/contest/') && !duelRoom) || (to === '/duels' && duelRoom) || (to === '/problems' && location.pathname.startsWith('/problem/') && !duelRoom) || (to === '/submissions' && location.pathname.startsWith('/submission/')) ? 'active' : undefined}><span className="nav-icon" aria-hidden="true">{icon}</span><span>{title}</span></NavLink>);
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
      <div className="header-notifications"><Notifications /></div>
      <div className="account">{account()}</div>
      <details ref={menu} className="mobile-nav"><summary>Меню</summary><nav aria-label="Мобильная навигация">{links()}{account()}</nav></details>
    </div></header>
    <main id="content" ref={main} tabIndex={-1} className="container">
      <div className="global-feedback">
      <RuntimeNotice />
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
          <Route path="/contests/new" element={new URLSearchParams(location.search).get('format') === 'duel' ? <Navigate to="/duels/new" replace /> : <ContestEditor />} />
          <Route path="/duels" element={<Duels />} />
          <Route path="/duels/new" element={<NewDuel />} />
          <Route path="/contest/:id" element={<Contest />} />
          <Route path="/contest/:id/edit" element={<ContestEditor />} />
          <Route path="/login" element={<><Problems /><AuthLanding mode="login" /></>} />
          <Route path="/register" element={<><Problems /><AuthLanding mode="register" /></>} />
          <Route path="/rating" element={<Rating />} />
          <Route path="/virtual" element={<Virtual />} />
          <Route path="/virtual/:id" element={<Virtual />} />
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