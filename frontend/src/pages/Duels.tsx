import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Empty, LoginRequired, PageHeader, Pagination, QueryState, Tabs, UserLink } from '../ui';
import { dateTime, difficultyLabels } from '../utils';
import { t } from '../i18n';

const limit = 20;

export default function Duels() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const history = params.get('tab') === 'history';
  const processor = params.get('processor') ?? '';
  const catalog = useProcessors();
  const offset = Math.max(0, Number.parseInt(params.get('offset') ?? '0', 10) || 0);
  const duels = useQuery({
    queryKey: ['duels', user?.id, history, processor, offset],
    queryFn: ({ signal }) => api.listDuels({ processor: processor || undefined, phase: history ? 'finished' : 'active', offset, limit }, signal),
    enabled: Boolean(user) && !isLoading,
    refetchInterval: history ? false : 5000,
  });
  return <>
    <PageHeader title={t('Дуэли')} actions={<Link className="btn primary" to="/duels/new">{t('Новая дуэль')}</Link>} />
    <Tabs selected={history ? 'history' : 'active'} items={[{ id: 'active', label: t('Текущие'), to: `/duels${processor ? `?${new URLSearchParams({ processor })}` : ''}` }, { id: 'history', label: t('История'), to: `/duels?${new URLSearchParams({ tab: 'history', ...(processor ? { processor } : {}) })}` }]} />
    <div className="toolbar"><select aria-label={t('Процессор дуэлей')} value={processor} onChange={event => setParams({ ...(history ? { tab: 'history' } : {}), ...(event.target.value ? { processor: event.target.value } : {}) })}><option value="">{t('Все процессоры')}</option>{catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></div>
    {!user && !isLoading ? <LoginRequired>{t('Войдите, чтобы увидеть свои дуэли и приглашения.')}</LoginRequired> : <QueryState query={duels}>
      {duels.data && (duels.data.items.length ? <div className="panel table-wrap"><table className="data-table duel-list" aria-label={t('Мои дуэли')}>
        <thead><tr><th>{t('Участники')}</th><th>{t('Задача')}</th><th>{t('Процессор')}</th><th>{t('Статус')}</th><th>{history ? t('Завершена, МСК') : t('До, МСК')}</th><th aria-label={t('Открыть')} /></tr></thead>
        <tbody>{duels.data.items.map(duel => <tr key={duel.id}>
          <td><UserLink user={duel.creator} />{duel.opponent ? <> vs <UserLink user={duel.opponent} /></> : <span className="small muted"> {t('- поиск соперника')}</span>}</td>
          <td>{duel.problemTitle ?? t('Случайная задача')}{duel.difficulty && <div className="subline">{difficultyLabels[duel.difficulty]}</div>}</td>
          <td>{catalog.title(duel.processor)}</td>
          <td>{duel.surrenderedUserId ? t('Завершена сдачей') : duel.status === 'matched' ? history ? t('Завершена') : t('Идет') : ({ queued: t('Поиск'), invited: t('Приглашение'), declined: t('Отклонено'), cancelled: t('Отменено'), expired: t('Истек срок') })[duel.status]}</td>
          <td>{history && ['cancelled', 'declined'].includes(duel.status) ? '-' : <time dateTime={duel.expiresAt}>{dateTime(duel.expiresAt)}</time>}</td>
          <td><Link className="text-link" to={duel.contestId ? `/contest/${encodeURIComponent(duel.contestId)}` : `/duels/new?request=${encodeURIComponent(duel.id)}`}>{duel.contestId ? t('Открыть дуэль') : duel.mode === 'invite' ? t('Открыть приглашение') : t('Открыть поиск')}</Link></td>
        </tr>)}</tbody>
      </table></div> : <Empty>{history ? t('Завершенных дуэлей пока нет.') : t('Текущих дуэлей нет. Найдите соперника или отправьте приглашение.')}</Empty>)}
      {duels.data && <Pagination total={duels.data.total} offset={offset} limit={limit} onChange={value => setParams({ ...(history ? { tab: 'history' } : {}), ...(processor ? { processor } : {}), ...(value ? { offset: String(value) } : {}) })} />}
    </QueryState>}
  </>;
}
