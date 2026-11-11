import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { Submission, SubmissionQuery } from '../api/types';
import { useSession } from '../auth';
import { Empty, ErrorMessage, LoginRequired, PageHeader, Pagination, QueryState, Status, TimeValue, UserLink } from '../ui';
import { dateTime, pendingStatuses, problemPath, statusLabels } from '../utils';

const limit = 20;

export function SubmissionTable({ items, inProblem = false, showUser = false }: { items: Submission[]; inProblem?: boolean; showUser?: boolean }) {
  return <div className="panel table-wrap"><table className="data-table" aria-label="История посылок">
    <thead><tr><th className="num">Посылка</th>{!inProblem && <th>Задача</th>}{showUser && <th>Участник</th>}<th>Вердикт</th><th className="num">Время GPU, мс</th><th>Отправлено, МСК</th></tr></thead>
    <tbody>{items.map(item => <tr key={item.id}>
      <td className="num mono"><Link to={`/submission/${encodeURIComponent(item.id)}`}>#{item.id}</Link></td>
      {!inProblem && <td><Link to={problemPath(item.problemId, item.contestId || 'practice')}>{item.problemTitle}</Link><div className="subline">{item.contestId ? <Link to={`/contest/${encodeURIComponent(item.contestId)}`}>{item.contestTitle || 'Контест'}</Link> : 'Практика'}</div></td>}
      {showUser && <td><UserLink user={item.user} /></td>}
      <td><Status status={item.status} />{item.status === 'accepted' && !item.comparable && <div className="subline">Старый профиль проверки</div>}</td>
      <td className="num mono"><TimeValue value={item.status === 'accepted' ? item.timeMs : null} /></td>
      <td><time dateTime={item.createdAt}>{dateTime(item.createdAt)}</time></td>
    </tr>)}</tbody>
  </table></div>;
}

export function SubmissionList({ problemId, context }: { problemId?: string; context?: string }) {
  const { user, isLoading } = useSession();
  const queryClient = useQueryClient();
  const previous = useRef(new Map<string, string>());
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const target = problemId ? user?.id : params.get('user') || user?.id;
  const offset = Math.max(0, Number.parseInt(params.get('offset') || '0', 10) || 0);
  const query: SubmissionQuery = {
    userId: target,
    problemId: problemId || params.get('problem') || undefined,
    context: context || params.get('contest') || 'all',
    status: params.get('verdict') || undefined,
    offset,
    limit,
  };
  const submissions = useQuery({
    queryKey: ['submissions', user?.id, query],
    queryFn: ({ signal }) => api.listSubmissions(query, signal),
    enabled: !isLoading && Boolean(target),
    refetchInterval: q => q.state.data?.items.some(item => pendingStatuses.has(item.status)) ? 2500 : false,
  });
  const choices = useQuery({
    queryKey: ['problem-filter-options', user?.id],
    queryFn: ({ signal }) => api.allProblems({}, signal),
    enabled: !problemId && Boolean(target) && !isLoading,
  });
  useEffect(() => {
    const items = submissions.data?.items;
    if (!items) return;
    if (items.some(item => pendingStatuses.has(previous.current.get(item.id) || '') && !pendingStatuses.has(item.status))) {
      void queryClient.invalidateQueries({ predicate: q => q.queryKey[0] !== 'submissions' && q.queryKey[0] !== 'submission' });
    }
    previous.current = new Map(items.map(item => [item.id, item.status]));
  }, [submissions.data, queryClient]);
  const contests = useQuery({
    queryKey: ['contest-filter-options', user?.id],
    queryFn: ({ signal }) => api.listContests({}, signal),
    enabled: !problemId && Boolean(target) && !isLoading,
  });
  function patch(name: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete('offset');
    value ? next.set(name, value) : next.delete(name);
    setParams(next);
  }
  if (isLoading) return <p className="muted" role="status">Загрузка...</p>;
  if (!target) return <LoginRequired returnTo={location.pathname + location.search}>Войдите, чтобы посмотреть свои посылки.</LoginRequired>;
  const options = new Map((choices.data || []).map(item => [item.id, item.title]));
  submissions.data?.items.forEach(item => options.set(item.problemId, item.problemTitle));
  if (query.problemId && !options.has(query.problemId)) options.set(query.problemId, query.problemId);
  const contestOptions = new Map((contests.data || []).map(item => [item.id, item.title]));
  submissions.data?.items.forEach(item => { if (item.contestId) contestOptions.set(item.contestId, item.contestTitle || item.contestId); });
  if (query.context && query.context !== 'all' && query.context !== 'practice' && !contestOptions.has(query.context)) contestOptions.set(query.context, query.context);
  const filtered = Boolean(query.status || !problemId && (query.problemId || query.context !== 'all'));
  return <>
    <div className="toolbar">
      {!problemId && <>
        <select aria-label="Задача" value={params.get('problem') || ''} onChange={event => patch('problem', event.target.value)}>
          <option value="">Все задачи</option>{[...options].map(([id, title]) => <option key={id} value={id}>{title}</option>)}
        </select>
        <select aria-label="Контекст посылки" value={params.get('contest') || 'all'} onChange={event => patch('contest', event.target.value === 'all' ? '' : event.target.value)}>
          <option value="all">Практика и контесты</option><option value="practice">Практика</option>{[...contestOptions].map(([id, title]) => <option key={id} value={id}>{title}</option>)}
        </select>
      </>}
      <select aria-label="Вердикт" value={params.get('verdict') || ''} onChange={event => patch('verdict', event.target.value)}>
        <option value="">Все вердикты</option>{Object.entries(statusLabels).map(([id, title]) => <option key={id} value={id}>{title}</option>)}
      </select>
    </div>
    <ErrorMessage error={choices.error || contests.error} />
    <QueryState query={submissions}>
      {submissions.data && <>{submissions.data.items.length ?
        <SubmissionTable items={submissions.data.items} inProblem={Boolean(problemId)} />
        : <Empty>{filtered ? 'Посылки не найдены. Измените фильтры.' : problemId ? <><p>Посылок этой задачи пока нет.</p><Link className="btn secondary" to={problemPath(problemId, context)}>Отправить решение</Link></> : <><p>Посылок пока нет.</p><Link className="btn secondary" to="/problems">Выбрать задачу</Link></>}</Empty>}
        <Pagination total={submissions.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}

export default function Submissions() {
  const { user } = useSession();
  const [params] = useSearchParams();
  const target = params.get('user');
  return <><PageHeader title={target && target !== user?.id ? 'Посылки участника' : 'Мои посылки'} /><SubmissionList /></>;
}
