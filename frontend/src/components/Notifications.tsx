import { useEffect, useRef, useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate } from 'react-router';
import { api } from '../api/client';
import type { AppNotification } from '../api/types';
import { useSession } from '../auth';
import { Empty, ErrorMessage } from '../ui';
import { dateTime } from '../utils';
import { t } from '../i18n';

function NotificationItem({ item, close }: { item: AppNotification; close: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useSession();
  const cache = useQueryClient();
  const mounted = useRef(false);
  const current = useRef({ key: location.key, userId: user?.id });
  current.current = { key: location.key, userId: user?.id };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const invitation = item.type === 'duel_invitation';
  const action = useMutation({
    mutationFn: (input: { kind: 'accept' | 'decline'; key: string; userId: string | undefined }) => input.kind === 'accept' ? api.acceptDuel(item.duelRequestId) : api.declineDuel(item.duelRequestId),
    onSuccess: (result, input) => {
      cache.setQueryData(['duel-request', result.id, input.userId], result);
      void cache.invalidateQueries({ queryKey: ['notifications'] });
      void cache.invalidateQueries({ queryKey: ['current-duel'] });
      void cache.invalidateQueries({ queryKey: ['duels'] });
      if (mounted.current && current.current.key === input.key && current.current.userId === input.userId && result.status === 'matched' && result.contestId && Date.parse(result.expiresAt) > Date.now()) { close(); navigate(`/contest/${encodeURIComponent(result.contestId)}`); }
    },
  });
  const status = action.data?.status ?? item.duelStatus;
  const contestId = item.contestId ?? action.data?.contestId;
  return <li className={item.readAt ? 'notification-item' : 'notification-item unread'}>
    <p>{item.message}</p><time className="small muted" dateTime={item.createdAt}>{dateTime(item.createdAt)} {t('МСК')}</time>
    <ErrorMessage error={action.error} />
    {invitation && status === 'invited' && <div className="notification-actions"><button className="btn primary" disabled={action.isPending} onClick={() => action.mutate({ kind: 'accept', key: location.key, userId: user?.id })}>{t('Принять дуэль')}</button><button className="btn secondary" disabled={action.isPending} onClick={() => action.mutate({ kind: 'decline', key: location.key, userId: user?.id })}>{t('Отклонить')}</button></div>}
    {status === 'cancelled' && <p className="small muted" role="status">{t('Приглашение отменено.')}</p>}
    {status === 'declined' && <p className="small muted" role="status">{t('Приглашение отклонено.')}</p>}
    {status === 'expired' && <p className="small muted" role="status">{t('Срок приглашения истек.')}</p>}
    {contestId && <Link className="text-link" to={`/contest/${encodeURIComponent(contestId)}`} onClick={close}>{t('Открыть дуэль')}</Link>}
  </li>;
}

export default function Notifications() {
  const { user } = useSession();
  const [opened, setOpened] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const attempted = useRef(new Set<string>());
  const location = useLocation();
  const cache = useQueryClient();
  const notifications = useInfiniteQuery({
    queryKey: ['notifications', user?.id],
    queryFn: ({ signal, pageParam }) => api.listNotifications({ offset: pageParam, limit: 30 }, signal),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => { const loaded = pages.reduce((sum, page) => sum + page.items.length, 0); return last.items.length && loaded < last.total ? loaded : undefined; },
    enabled: Boolean(user), refetchInterval: 5000,
  });
  const items = [...new Map((notifications.data?.pages.flatMap(page => page.items) ?? []).map(item => [item.id, item])).values()];
  const read = useMutation({
    mutationFn: (ids: string[]) => Promise.all(ids.map(id => api.readNotification(id))),
    onSuccess: () => cache.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const markRead = read.mutate;
  useEffect(() => { setOpened(false); attempted.current.clear(); }, [user?.id, location.pathname, location.search]);
  useEffect(() => {
    if (!opened) return;
    panel.current?.focus();
    function key(event: KeyboardEvent) { if (event.key === 'Escape') { setOpened(false); button.current?.focus(); } }
    function outside(event: PointerEvent) { if (event.target instanceof Node && !root.current?.contains(event.target)) setOpened(false); }
    document.addEventListener('keydown', key);
    document.addEventListener('pointerdown', outside);
    return () => { document.removeEventListener('keydown', key); document.removeEventListener('pointerdown', outside); };
  }, [opened]);
  useEffect(() => {
    if (!opened) return;
    const unread = notifications.data?.pages.flatMap(page => page.items).filter(item => !item.readAt && !attempted.current.has(item.id)).map(item => item.id) ?? [];
    if (unread.length) { unread.forEach(id => attempted.current.add(id)); markRead(unread); }
  }, [opened, notifications.data, markRead]);
  if (!user) return null;
  const count = notifications.data?.pages[0]?.unreadCount ?? 0;
  return <div className="notifications" ref={root}>
    <button ref={button} className="notification-toggle" title={t('Уведомления')} aria-label={t('Уведомления{0}', [count ? t(': {0} непрочитанных', [count]) : ''])} aria-expanded={opened} aria-controls="notifications-panel" onClick={() => { attempted.current.clear(); read.reset(); setOpened(value => !value); }}>
      <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>{count > 0 && <span className="notification-count" aria-hidden="true">{count > 99 ? '99+' : count}</span>}
    </button>
    {opened && <div className="notifications-panel" id="notifications-panel" role="dialog" aria-label={t('Уведомления')} tabIndex={-1} ref={panel}>
      <div className="notifications-heading"><h2>{t('Уведомления')}</h2><button className="text-link" aria-label={t('Закрыть уведомления')} onClick={() => { setOpened(false); button.current?.focus(); }}>{t('Закрыть')}</button></div>
      <ErrorMessage error={notifications.error || read.error} />
      {notifications.error && <button className="text-link" onClick={() => void notifications.refetch()}>{t('Повторить загрузку')}</button>}
      {read.error && <button className="text-link" onClick={() => read.mutate(items.filter(item => !item.readAt).map(item => item.id))}>{t('Отметить прочитанными')}</button>}
      {notifications.isPending ? <p role="status">{t('Загрузка уведомлений...')}</p> : items.length ? <ul className="notification-list">{items.map(item => <NotificationItem key={item.id} item={item} close={() => setOpened(false)} />)}</ul> : !notifications.error && <Empty>{t('Уведомлений пока нет.')}</Empty>}
      {notifications.hasNextPage && <button className="btn secondary notification-more" disabled={notifications.isFetchingNextPage} onClick={() => void notifications.fetchNextPage()}>{notifications.isFetchingNextPage ? t('Загружаем...') : t('Загрузить еще')}</button>}
    </div>}
  </div>;
}
