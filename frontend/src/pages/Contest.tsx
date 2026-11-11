import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { Problem } from '../api/types';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Empty, ErrorMessage, Markdown, PageHeader, Status, Tabs, UserLink } from '../ui';
import { dateTime, difficultyLabels, phaseOf, problemPath, solveTime, statusLabels } from '../utils';

function countdown(value: string, now: number) {
  const seconds = Math.max(0, Math.ceil((Date.parse(value) - now) / 1000));
  const days = Math.floor(seconds / 86400);
  const time = [Math.floor(seconds % 86400 / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(part => String(part).padStart(2, '0')).join(':');
  return days ? `${days} д ${time}` : time;
}

function taskLabel(index: number) {
  let value = index + 1;
  let label = '';
  while (value > 0) {
    value -= 1;
    label = String.fromCharCode(65 + value % 26) + label;
    value = Math.floor(value / 26);
  }
  return label;
}

function PersonalStatus({ problem }: { problem: Problem }) {
  // in a contest a problem shows the contest's processor only
  const best = problem.processors[0]?.bestSubmissionId ?? null;
  const accepted = best !== null;
  const mainId = accepted ? best : problem.lastSubmissionId;
  if (!mainId) return <span className="muted">Не решена</span>;
  const old = !accepted && problem.lastStatus === 'accepted';
  return <>
    <Link to={`/submission/${encodeURIComponent(mainId)}`} className={old ? 'muted' : undefined}>{old ? 'Принято, старая версия задачи' : <Status status={accepted ? 'accepted' : problem.lastStatus ?? ''} />}</Link>
    {accepted && problem.lastSubmissionId && problem.lastSubmissionId !== best && problem.lastStatus !== 'accepted' && <div className="small"><Link to={`/submission/${encodeURIComponent(problem.lastSubmissionId)}`}>Последняя: {statusLabels[problem.lastStatus ?? ''] ?? problem.lastStatus}</Link></div>}
  </>;
}

export default function Contest() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const { user, isLoading } = useSession();
  const location = useLocation();
  const queryClient = useQueryClient();
  const catalog = useProcessors();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const contest = useQuery({
    queryKey: ['contest', id, user?.id],
    queryFn: ({ signal }) => api.getContest(id, signal),
    enabled: Boolean(id) && !isLoading,
    refetchInterval: query => query.state.data && phaseOf(query.state.data) !== 'finished' ? 5000 : false,
  });
  const item = contest.data;
  const phase = item ? phaseOf(item) : 'upcoming';
  const author = Boolean(user && item?.authors.some(person => person.id === user.id));
  const selected = ['tasks', 'standings', 'rules'].find(tab => tab === params.get('tab')) ?? 'tasks';
  const tasks = useQuery({
    queryKey: ['contest-tasks', id, user?.id],
    queryFn: ({ signal }) => api.allProblems({ context: id }, signal),
    enabled: Boolean(item) && (phase !== 'upcoming' || author),
    refetchInterval: phase === 'live' ? 5000 : false,
  });
  const standings = useQuery({
    queryKey: ['standings', id, user?.id],
    queryFn: ({ signal }) => api.contestStandings(id, signal),
    enabled: Boolean(item) && phase !== 'upcoming' && selected === 'standings',
    refetchInterval: phase === 'live' ? 5000 : false,
  });
  const previousPhase = useRef<{ id: string; phase: string } | null>(null);
  useEffect(() => {
    if (!item) return;
    const previous = previousPhase.current;
    previousPhase.current = { id, phase };
    if (!previous || previous.id !== id || previous.phase === phase) return;
    void queryClient.invalidateQueries({ queryKey: ['contest', id] });
    void queryClient.invalidateQueries({ queryKey: ['contest-tasks', id] });
    void queryClient.invalidateQueries({ queryKey: ['standings', id] });
  }, [id, item, phase, queryClient]);
  const join = useMutation({
    mutationFn: () => api.registerForContest(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contest', id] });
      void queryClient.invalidateQueries({ queryKey: ['contests'] });
      void queryClient.invalidateQueries({ queryKey: ['profile'] });
    },
  });
  const resetJoin = join.reset;
  useEffect(() => resetJoin(), [id, resetJoin]);

  if (contest.isPending) return <p className="loading" role="status">Загрузка контеста...</p>;
  if (contest.error && !item) return <><PageHeader title="Контест" /><ErrorMessage error={contest.error} /><button className="text-link" onClick={() => void contest.refetch()}>Повторить</button><p><Link to="/contests">К соревнованиям</Link></p></>;
  if (!item) return <Empty>Контест не найден.</Empty>;

  const base = `/contest/${encodeURIComponent(id)}`;
  const actions = <>
    {phase !== 'finished' && (item.registered ? <span className="success">{phase === 'upcoming' ? 'Вы зарегистрированы' : 'Вы участвуете'}</span> : user ? <button className="btn secondary" disabled={join.isPending} onClick={() => join.mutate()}>{join.isPending ? 'Регистрация...' : 'Зарегистрироваться'}</button> : <Link className="btn secondary" to={`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`}>Зарегистрироваться</Link>)}
    {author && phase === 'upcoming' && <Link className="btn secondary" to={`${base}/edit`}>Изменить</Link>}
  </>;
  const columns = tasks.data ?? [];

  return <>
    <PageHeader title={item.title} actions={actions} />
    {contest.error && <ErrorMessage error={contest.error} />}
    <div className="toolbar small contest-meta">
      <span className="muted">{dateTime(item.startAt)} - {dateTime(item.endAt)} МСК</span>
      <span>{phase === 'finished' ? 'Завершен' : <>{phase === 'upcoming' ? 'До начала' : 'До конца'} <time>{countdown(phase === 'upcoming' ? item.startAt : item.endAt, now)}</time></>}</span>
      <span className="muted">Процессор: {catalog.title(item.processor)}{catalog.find(item.processor) && <> · {catalog.find(item.processor)?.model}</>}</span>
      <span className="muted">Авторы: {item.authors.map((person, index) => <span key={person.id}>{index > 0 && ', '}<UserLink user={person} /></span>)}</span>
    </div>
    {join.error && <ErrorMessage error={join.error} />}
    <Tabs selected={selected} items={[{ id: 'tasks', label: 'Задачи', to: `${base}?tab=tasks` }, { id: 'standings', label: 'Таблица', to: `${base}?tab=standings` }, { id: 'rules', label: 'Правила', to: `${base}?tab=rules` }]} />
    {selected === 'rules' ? item.rulesMarkdown ? <Markdown>{item.rulesMarkdown}</Markdown> : <Empty>Правила пока недоступны.</Empty> : phase === 'upcoming' && (selected === 'standings' || !author) ? <Empty>{selected === 'standings' ? 'Таблица появится после начала.' : `Задачи откроются ${dateTime(item.startAt)} МСК.`}</Empty> : <>
      {tasks.isPending && <p className="loading" role="status">Загрузка задач...</p>}
      {tasks.error && <div><ErrorMessage error={tasks.error} /><button className="text-link" onClick={() => void tasks.refetch()}>Повторить</button></div>}
      {selected === 'tasks' && tasks.data && <>
        {phase === 'finished' && <p className="notice">Контест завершен. На странице задачи можно перейти к решению в практике.</p>}
        {tasks.data.length ? <div className="table-wrap"><table className="data-table" aria-label="Задачи соревнования"><thead><tr><th>Задача</th><th>Сложность</th>{user && <th>Ваш статус</th>}</tr></thead><tbody>{tasks.data.map((problem, index) => <tr key={problem.id}>
          <td><Link to={problemPath(problem.id, id)}>{taskLabel(index)}. {problem.title}</Link></td>
          <td>{difficultyLabels[problem.difficulty]}</td>
          {user && <td><PersonalStatus problem={problem} /></td>}
        </tr>)}</tbody></table></div> : <Empty>В контесте пока нет задач.</Empty>}
      </>}
      {selected === 'standings' && <>
        <p className="small muted">{phase === 'finished' ? 'Итоговая таблица.' : 'Результаты обновляются после проверки посылок.'}</p>
        {standings.isPending && <p className="loading" role="status">Загрузка таблицы...</p>}
        {standings.error && <div><ErrorMessage error={standings.error} /><button className="text-link" onClick={() => void standings.refetch()}>Повторить</button></div>}
        {standings.data && (standings.data.length ? <div className="table-wrap"><table className="data-table" aria-label="Таблица контеста"><thead><tr><th>Место</th><th>Участник</th><th>Решено</th><th>Время</th>{columns.map((problem, index) => <th key={problem.id}><Link title={problem.title} to={problemPath(problem.id, id)}>{taskLabel(index)}</Link></th>)}</tr></thead><tbody>{standings.data.map(row => <tr key={row.user.id} className={row.user.id === user?.id ? 'own-row' : undefined}>
          <td>{row.rank}</td><td><UserLink user={row.user} />{row.user.id === user?.id && <span className="small muted"> вы</span>}</td><td>{row.solved}</td><td className="mono">{solveTime(row.totalSeconds)}</td>
          {columns.map(problem => <td key={problem.id} className="mono">{row.times[problem.id] === undefined ? '-' : solveTime(row.times[problem.id])}</td>)}
        </tr>)}</tbody></table></div> : <Empty>Результатов пока нет.</Empty>)}
      </>}
    </>}
  </>;
}
