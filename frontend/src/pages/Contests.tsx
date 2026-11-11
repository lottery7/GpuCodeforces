import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { Contest, ContestQuery } from '../api/types';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Empty, ErrorMessage, LoginRequired, PageHeader, SearchInput, Tabs } from '../ui';
import { canJoinContest, dateTime, phaseOf } from '../utils';

const phases = ['live', 'upcoming', 'finished', 'mine'] as const;

function duration(contest: Contest) {
  const minutes = Math.round((Date.parse(contest.endAt) - Date.parse(contest.startAt)) / 60000);
  const hours = Math.floor(minutes / 60);
  return `${hours ? `${hours} ч` : ''}${hours && minutes % 60 ? ' ' : ''}${minutes % 60 ? `${minutes % 60} мин` : ''}`;
}

export default function Contests() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const selected = phases.find(phase => phase === params.get('tabs')) ?? 'live';
  const query = params.get('q') ?? '';
  const processor = params.get('processor') ?? '';
  const catalog = useProcessors();
  const filter: ContestQuery = { q: query || undefined, processor: processor || undefined, ...(selected === 'mine' ? { mine: true } : { phase: selected }) };
  const contests = useQuery({
    queryKey: ['contests', user?.id, selected, query, processor],
    queryFn: ({ signal }) => api.listContests(filter, signal),
    enabled: !isLoading && (selected !== 'mine' || Boolean(user)),
    refetchInterval: selected === 'live' ? 5000 : false,
  });
  const join = useMutation({
    mutationFn: (id: string) => api.registerForContest(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contests'] });
      void queryClient.invalidateQueries({ queryKey: ['contest'] });
      void queryClient.invalidateQueries({ queryKey: ['profile'] });
    },
  });
  const resetJoin = join.reset;
  useEffect(() => resetJoin(), [selected, query, processor, user?.id, resetJoin]);
  const tabPath = (tab: string) => `/contests?${new URLSearchParams({ tabs: tab, ...(query ? { q: query } : {}), ...(processor ? { processor } : {}) })}`;
  const filterBy = (values: { q?: string; processor?: string }) => {
    const next = { q: query, processor, ...values };
    setParams({ tabs: selected, ...(next.q ? { q: next.q } : {}), ...(next.processor ? { processor: next.processor } : {}) }, { replace: true });
  };
  const returnTo = location.pathname + location.search;
  const entries = [
    { id: 'live', label: 'Текущие', to: tabPath('live') },
    { id: 'upcoming', label: 'Будущие', to: tabPath('upcoming') },
    { id: 'finished', label: 'Завершенные', to: tabPath('finished') },
    ...(user || selected === 'mine' ? [{ id: 'mine', label: 'Мои', to: tabPath('mine') }] : []),
  ];

  return <>
    <PageHeader title="Соревнования" actions={<><Link className="btn secondary" to={user ? '/duels/new' : '/login?returnTo=%2Fduels%2Fnew'}>Создать дуэль</Link><Link className="btn primary" to={user ? '/contests/new' : '/login?returnTo=%2Fcontests%2Fnew'}>Создать контест</Link></>} />
    <Tabs items={entries} selected={selected} />
    {selected === 'mine' && !user && !isLoading ? <LoginRequired returnTo={returnTo}>Здесь будут контесты, в которых вы указаны автором.</LoginRequired> : <>
      <div className="toolbar">
        <div className="search"><SearchInput label="Найти соревнование" placeholder="Название контеста" value={query} onChange={value => filterBy({ q: value })} /></div>
        <select aria-label="Процессор" value={processor} onChange={event => filterBy({ processor: event.target.value })}>
          <option value="">Все процессоры</option>
          {catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
        </select>
        {(query || processor) && <Link className="filter-reset" to={`/contests?tabs=${selected}`}>Сбросить</Link>}
      </div>
      {contests.isPending && <p className="loading" role="status">Загрузка соревнований...</p>}
      {contests.error && <div><ErrorMessage error={contests.error} /><button className="text-link" onClick={() => void contests.refetch()}>Повторить</button></div>}
      {contests.data && (contests.data.length ? <div className="table-wrap"><table className="data-table contest-list" aria-label="Список соревнований">
        <thead><tr><th>Название</th><th>Процессор</th><th>Начало, МСК</th><th>Длительность</th><th>Задачи</th>{selected === 'mine' ? <th>Статус</th> : selected !== 'finished' && <th>Участие</th>}</tr></thead>
        <tbody>{contests.data.map(contest => <tr key={contest.id}>
          <td><Link to={`/contest/${encodeURIComponent(contest.id)}`}>{contest.title}</Link>{contest.format === 'duel' && <div className="subline">Дуэль</div>}</td>
          <td>{catalog.title(contest.processor)}</td>
          <td><time dateTime={contest.startAt}>{dateTime(contest.startAt)}</time></td>
          <td>{duration(contest)}</td><td>{contest.problemCount}</td>
          {selected === 'mine' ? <td>{({ upcoming: 'До начала', live: 'Идет', finished: 'Завершен' })[phaseOf(contest)]}</td> : selected !== 'finished' && <td className="contest-registration">
            {contest.registered ? <span className="success">{selected === 'upcoming' ? 'Вы зарегистрированы' : 'Вы участвуете'}</span> : !canJoinContest(contest, user?.id) ? <span className="muted">По приглашению</span> : user ? <button className="btn secondary" disabled={join.isPending} onClick={() => join.mutate(contest.id)}>{join.isPending && join.variables === contest.id ? 'Регистрация...' : contest.format === 'duel' ? 'Принять дуэль' : 'Зарегистрироваться'}</button> : <Link className="btn secondary" to={`/login?returnTo=${encodeURIComponent(returnTo)}`}>Зарегистрироваться</Link>}
            {join.variables === contest.id && <ErrorMessage error={join.error} reveal />}
          </td>}
        </tr>)}</tbody>
      </table></div> : <Empty>{query.trim() || processor ? 'Соревнования не найдены. Измените запрос или выберите другую вкладку.' : selected === 'mine' ? 'У вас пока нет контестов.' : selected === 'live' ? 'Сейчас нет активных соревнований.' : selected === 'upcoming' ? 'Новые соревнования еще не объявлены.' : 'Завершенных соревнований пока нет.'}</Empty>)}
    </>}
  </>;
}
