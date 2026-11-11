import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { Contest } from '../api/types';
import { useSession } from '../auth';
import { useAuthDialog } from '../auth-dialog';
import { useProcessors } from '../processors';
import { Empty, ErrorMessage, PageHeader, SearchInput } from '../ui';
import { dateTime, phaseOf } from '../utils';

type PhaseFilter = 'now' | 'upcoming' | 'live' | 'finished' | 'all';
type ContestPhase = ReturnType<typeof phaseOf>;

const phaseFilters: { id: PhaseFilter; label: string; matches: ContestPhase[] }[] = [
  { id: 'now', label: 'Текущие и будущие', matches: ['upcoming', 'live'] },
  { id: 'upcoming', label: 'Будущие', matches: ['upcoming'] },
  { id: 'live', label: 'Текущие', matches: ['live'] },
  { id: 'finished', label: 'Завершенные', matches: ['finished'] },
  { id: 'all', label: 'Все', matches: ['upcoming', 'live', 'finished'] },
];

const phaseStatus: Record<ContestPhase, string> = { upcoming: 'До начала', live: 'Идет', finished: 'Завершен' };

function duration(contest: Contest) {
  const minutes = Math.round((Date.parse(contest.endAt) - Date.parse(contest.startAt)) / 60000);
  const hours = Math.floor(minutes / 60);
  return `${hours ? `${hours} ч` : ''}${hours && minutes % 60 ? ' ' : ''}${minutes % 60 ? `${minutes % 60} мин` : ''}`;
}

export default function Contests() {
  const { user, isLoading } = useSession();
  const openAuth = useAuthDialog();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const filter = phaseFilters.find(item => item.id === params.get('phase'))?.id ?? 'now';
  const query = params.get('q') ?? '';
  const processor = params.get('processor') ?? '';
  const catalog = useProcessors();
  const contests = useQuery({
    queryKey: ['contests', user?.id, filter, query, processor],
    queryFn: ({ signal }) => api.listContests({ q: query || undefined, processor: processor || undefined }, signal),
    enabled: !isLoading,
    refetchInterval: filter === 'now' || filter === 'live' ? 5000 : false,
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
  useEffect(() => resetJoin(), [filter, query, processor, user?.id, resetJoin]);
  const patch = (values: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) value ? next.set(key, value) : next.delete(key);
    setParams(next, { replace: true });
  };
  const matches = phaseFilters.find(item => item.id === filter)!.matches;
  const sorted = (contests.data ?? [])
    .filter(contest => matches.includes(phaseOf(contest)))
    .sort((a, b) => filter === 'finished' ? Date.parse(b.startAt) - Date.parse(a.startAt) : Date.parse(a.startAt) - Date.parse(b.startAt));
  const isMine = (contest: Contest) => Boolean(user) && contest.authors.some(author => author.id === user?.id);
  const mine = user ? sorted.filter(isMine) : [];
  const rest = (user ? sorted.filter(contest => !isMine(contest)) : sorted);
  const returnTo = location.pathname + location.search;

  const registration = (contest: Contest) => {
    const phase = phaseOf(contest);
    if (phase === 'finished') return <span className="muted">—</span>;
    if (contest.registered) return <span className="success">{phase === 'upcoming' ? 'Вы зарегистрированы' : 'Вы участвуете'}</span>;
    return user
      ? <button className="btn secondary" disabled={join.isPending} onClick={() => join.mutate(contest.id)}>{join.isPending && join.variables === contest.id ? 'Регистрация...' : 'Зарегистрироваться'}</button>
      : <button className="btn secondary" onClick={() => openAuth('login', returnTo)}>Зарегистрироваться</button>;
  };

  const rows = (group: Contest[]) => group.map(contest => <tr key={contest.id}>
    <td><Link to={`/contest/${encodeURIComponent(contest.id)}`}>{contest.title}</Link></td>
    <td>{catalog.title(contest.processor)}</td>
    <td><time dateTime={contest.startAt}>{dateTime(contest.startAt)}</time></td>
    <td className="num">{duration(contest)}</td><td className="num">{contest.problemCount}</td>
    <td>{phaseStatus[phaseOf(contest)]}</td>
    <td className="contest-registration">{registration(contest)}{join.variables === contest.id && <ErrorMessage error={join.error} reveal />}</td>
  </tr>);

  return <>
    <PageHeader title="Соревнования" actions={<Link className="btn primary" to={user ? '/contests/new' : '/login?returnTo=%2Fcontests%2Fnew'} onClick={!user ? event => { event.preventDefault(); openAuth('login', '/contests/new'); } : undefined}>Создать контест</Link>} />
    <div className="toolbar">
      <div className="search"><SearchInput label="Найти соревнование" placeholder="Название контеста" value={query} onChange={value => patch({ q: value })} /></div>
      <select aria-label="Показать соревнования" value={filter} onChange={event => patch({ phase: event.target.value })}>
        {phaseFilters.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
      </select>
      <select aria-label="Процессор" value={processor} onChange={event => patch({ processor: event.target.value })}>
        <option value="">Все процессоры</option>
        {catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      {(query || processor) && <button className="text-link filter-reset" onClick={() => setParams({ phase: filter })}>Сбросить</button>}
    </div>
    {contests.isPending && <p className="loading" role="status">Загрузка соревнований...</p>}
    {contests.error && <div><ErrorMessage error={contests.error} /><button className="text-link" onClick={() => void contests.refetch()}>Повторить</button></div>}
    {contests.data && (sorted.length ? <div className="table-wrap"><table className="data-table contest-list" aria-label="Список соревнований">
      <thead><tr><th>Название</th><th>Процессор</th><th>Начало, МСК</th><th className="num">Длительность</th><th className="num">Задачи</th><th>Статус</th><th>Участие</th></tr></thead>
      <tbody>
        {user && mine.length > 0 && <tr className="contest-group"><td colSpan={7}>Мои соревнования</td></tr>}
        {user && mine.length > 0 && rows(mine)}
        {rows(rest)}
      </tbody>
    </table></div> : <Empty>{query.trim() || processor ? 'Соревнования не найдены. Измените запрос или выберите другой фильтр.' : 'Соревнований пока нет.'}</Empty>)}
  </>;
}