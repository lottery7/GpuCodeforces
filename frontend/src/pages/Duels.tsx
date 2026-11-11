import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { Empty, LoginRequired, PageHeader, Pagination, QueryState, Tabs, UserLink } from '../ui';
import { dateTime, difficultyLabels } from '../utils';

const limit = 20;

export default function Duels() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const history = params.get('tab') === 'history';
  const offset = Math.max(0, Number.parseInt(params.get('offset') ?? '0', 10) || 0);
  const duels = useQuery({
    queryKey: ['duels', user?.id, history, offset],
    queryFn: ({ signal }) => api.listDuels({ phase: history ? 'finished' : 'active', offset, limit }, signal),
    enabled: Boolean(user) && !isLoading,
    refetchInterval: history ? false : 5000,
  });
  return <>
    <PageHeader title="Дуэли" actions={<Link className="btn primary" to="/duels/new">Новая дуэль</Link>} />
    <Tabs selected={history ? 'history' : 'active'} items={[{ id: 'active', label: 'Текущие', to: '/duels' }, { id: 'history', label: 'История', to: '/duels?tab=history' }]} />
    {!user && !isLoading ? <LoginRequired>Войдите, чтобы увидеть свои дуэли и приглашения.</LoginRequired> : <QueryState query={duels}>
      {duels.data && (duels.data.items.length ? <div className="panel table-wrap"><table className="data-table duel-list" aria-label="Мои дуэли">
        <thead><tr><th>Участники</th><th>Задача</th><th>Статус</th><th>{history ? 'Завершена, МСК' : 'До, МСК'}</th><th aria-label="Открыть" /></tr></thead>
        <tbody>{duels.data.items.map(duel => <tr key={duel.id}>
          <td><UserLink user={duel.creator} />{duel.opponent ? <> vs <UserLink user={duel.opponent} /></> : <span className="small muted"> - поиск соперника</span>}</td>
          <td>{duel.problemTitle ?? 'Случайная задача'}{duel.difficulty && <div className="subline">{difficultyLabels[duel.difficulty]}</div>}</td>
          <td>{duel.surrenderedUserId ? 'Завершена сдачей' : duel.status === 'matched' ? history ? 'Завершена' : 'Идет' : ({ queued: 'Поиск', invited: 'Приглашение', declined: 'Отклонено', cancelled: 'Отменено', expired: 'Истек срок' })[duel.status]}</td>
          <td><time dateTime={duel.expiresAt}>{dateTime(duel.expiresAt)}</time></td>
          <td><Link className="text-link" to={duel.contestId ? `/contest/${encodeURIComponent(duel.contestId)}` : `/duels/new?request=${encodeURIComponent(duel.id)}`}>{duel.contestId ? 'Открыть дуэль' : 'Открыть запрос'}</Link></td>
        </tr>)}</tbody>
      </table></div> : <Empty>{history ? 'Завершенных дуэлей пока нет.' : 'Текущих дуэлей нет. Найдите соперника или отправьте приглашение.'}</Empty>)}
      {duels.data && <Pagination total={duels.data.total} offset={offset} limit={limit} onChange={value => setParams({ ...(history ? { tab: 'history' } : {}), ...(value ? { offset: String(value) } : {}) })} />}
    </QueryState>}
  </>;
}
