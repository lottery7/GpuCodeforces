import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ApiError } from './api/client';
import { useAuthDialog } from './auth-dialog';
import type { Score } from './api/types';
import { formatScore, scoreUnits } from './processors';
import { difficultyLabels, pendingStatuses, statusLabels, userPath } from './utils';

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  useEffect(() => { document.title = 'GPU Forces - ' + title; }, [title]);
  return <div className="page-head"><div><h1>{title}</h1>{description && <p className="muted">{description}</p>}</div>{actions && <div className="inline">{actions}</div>}</div>;
}

export function useFeedbackVisibility<T extends HTMLElement = HTMLDivElement>(trigger: unknown, enabled = true) {
  const ref = useRef<T>(null);
  useLayoutEffect(() => {
    if (!trigger || !enabled || !ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const viewport = window.visualViewport;
    const top = (viewport?.offsetTop ?? 0) + 8;
    const bottom = (viewport?.offsetTop ?? 0) + (viewport?.height ?? window.innerHeight) - 8;
    if (rect.height > 0 && (rect.top < top || rect.bottom > bottom)) {
      ref.current.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    }
  }, [trigger, enabled]);
  return ref;
}

export function ErrorMessage({ error, reveal = false }: { error: unknown; reveal?: boolean }) {
  const location = useLocation();
  const openAuth = useAuthDialog();
  const ref = useFeedbackVisibility(error, reveal);
  if (!error) return null;
  return <div ref={ref} className="notice error feedback-anchor" role="alert"><div className="error-copy" tabIndex={0}>{error instanceof Error ? error.message : 'Не удалось выполнить запрос.'}</div>{error instanceof ApiError && error.status === 401 && !['/login', '/register'].includes(location.pathname) && <button className="text-link" onClick={() => openAuth('login', location.pathname + location.search)}>Войти</button>}</div>;
}

export function QueryState({ query, children }: { query: { isPending?: boolean; isLoading?: boolean; error: unknown; data?: unknown; refetch: () => unknown }; children?: ReactNode }) {
  if (query.isPending || query.isLoading) return <div className="empty" role="status">Загрузка...</div>;
  if (query.error && query.data === undefined) return <div className="stack"><ErrorMessage error={query.error} /><div><button className="btn secondary" onClick={() => void query.refetch()}>Повторить</button></div></div>;
  return <>{query.error && <ErrorMessage error={query.error} />}{children}</>;
}

export const Empty = ({ children }: { children: ReactNode }) => <div className="empty">{children}</div>;
export const Markdown = ({ children }: { children: string }) => <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown></div>;
/** A score with the unit of its type; without a score, a dash. */
export function ScoreValue({ score, unit = true }: { score: Score | null | undefined; unit?: boolean }) {
  if (!score) return <span className="mono">-</span>;
  return <span className="mono">{formatScore(score)}{unit && <span className="score-unit"> {scoreUnits[score.type]}</span>}</span>;
}
export function Status({ status }: { status: string }) {
  const tone = status === 'accepted' ? 'accepted' : pendingStatuses.has(status) ? 'pending' : 'failed';
  return <span className={`verdict ${tone}`}>{statusLabels[status] || status || '-'}</span>;
}
export const UserLink = ({ user }: { user: { id: string; handle: string } }) => <Link to={userPath(user.id)}>{user.handle}</Link>;
export function Difficulty({ difficulty }: { difficulty: string }) {
  const cls = difficulty === 'easy' ? 'difficulty-easy' : difficulty === 'medium' ? 'difficulty-medium' : difficulty === 'hard' ? 'difficulty-hard' : '';
  return <span className={`difficulty ${cls}`}>{difficultyLabels[difficulty] || difficulty}</span>;
}
export function RatingDelta({ value }: { value: number | null | undefined }) {
  const cls = value === null || value === undefined ? 'delta-zero' : value > 0 ? 'delta-up' : value < 0 ? 'delta-down' : 'delta-zero';
  return <span className={`rating-delta ${cls}`}>{value === null || value === undefined ? '-' : `${value > 0 ? '+' : ''}${value}`}</span>;
}
export function Tabs({ items, selected }: { items: { id: string; label: string; to: string }[]; selected: string }) {
  return <nav className="tabs" aria-label="Разделы страницы">{items.map(item => <Link key={item.id} className={`tab${item.id === selected ? ' active' : ''}`} aria-current={item.id === selected ? 'page' : undefined} to={item.to}>{item.label}</Link>)}</nav>;
}
export function SearchInput({ value, onChange, placeholder, label }: { value: string; onChange: (value: string) => void; placeholder: string; label: string }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  useEffect(() => {
    if (value === draft) return;
    const timer = window.setTimeout(() => onChange(draft), 250);
    return () => window.clearTimeout(timer);
  }, [draft, value, onChange]);
  return <input className="search-input" type="search" value={draft} aria-label={label} placeholder={placeholder} onChange={event => setDraft(event.target.value)} onKeyDown={event => {
    if (event.key === 'Enter') { event.preventDefault(); onChange(draft); }
    if (event.key === 'Escape') { setDraft(''); onChange(''); }
  }} />;
}
export function Pagination({ total, offset, limit, onChange }: { total: number; offset: number; limit: number; onChange: (value: number) => void }) {
  if (total <= limit && offset === 0) return null;
  const pages = Math.max(1, Math.ceil(total / limit));
  const current = Math.floor(offset / limit);
  const indices = [...new Set([0, ...Array.from({ length: 5 }, (_, index) => current - 2 + index).filter(index => index >= 0 && index < pages), pages - 1])].sort((a, b) => a - b);
  return <nav className="pagination" aria-label="Страницы списка">{indices.map((page, index) => <span key={page} className="page-item">{index > 0 && page - indices[index - 1] > 1 && <span className="muted">...</span>}<button type="button" aria-current={page === current ? 'page' : undefined} className={page === current ? 'active' : undefined} onClick={() => onChange(page * limit)}>{page + 1}</button></span>)}</nav>;
}
export function LoginRequired({ returnTo, children }: { returnTo?: string; children?: ReactNode }) {
  const location = useLocation();
  const openAuth = useAuthDialog();
  return <div className="empty"><p>{children || 'Войдите, чтобы продолжить.'}</p><button className="btn primary" onClick={() => openAuth('login', returnTo || location.pathname + location.search)}>Войти</button></div>;
}
