import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { ProblemQuery } from '../api/types';
import { useSession } from '../auth';
import { Difficulty, Empty, PageHeader, Pagination, QueryState, SearchInput, TimeValue } from '../ui';
import { difficultyLabels, problemPath } from '../utils';

const limit = 20;

export default function Problems() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const target = params.get('user') || undefined;
  const difficulty = params.get('difficulty') || '';
  const sort = params.get('sort') || 'code';
  const status = params.get('status') || '';
  const offset = Math.max(0, Number.parseInt(params.get('offset') || '0', 10) || 0);
  const query: ProblemQuery = {
    context: 'practice',
    q: params.get('q') || undefined,
    difficulty: difficulty === 'easy' || difficulty === 'medium' || difficulty === 'hard' ? difficulty : undefined,
    tag: params.get('tag') || undefined,
    solved: status === 'solved' ? true : status === 'unsolved' ? false : undefined,
    sort: sort === 'difficulty' || sort === 'bestTime' ? sort : 'code',
    userId: target,
    offset,
    limit,
  };
  const problems = useQuery({
    queryKey: ['problems', user?.id, query],
    queryFn: ({ signal }) => api.listProblems(query, signal),
    enabled: !isLoading,
  });
  function patch(values: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    next.delete('offset');
    for (const [key, value] of Object.entries(values)) value ? next.set(key, value) : next.delete(key);
    setParams(next, { replace: true });
  }
  const personal = Boolean(user || target);
  const hasFilters = Boolean(params.get('q') || difficulty || params.get('tag') || status);
  return <>
    <PageHeader title={target ? 'Задачи участника' : 'Задачи'} />
    <div className="toolbar">
      <SearchInput value={params.get('q') || ''} onChange={value => patch({ q: value })} placeholder="Название или номер задачи" label="Найти задачу" />
      <select aria-label="Сложность" value={difficulty} onChange={event => patch({ difficulty: event.target.value })}>
        <option value="">Сложность</option>
        {Object.entries(difficultyLabels).map(([value, title]) => <option key={value} value={value}>{title}</option>)}
      </select>
      {personal && <select aria-label="Статус решения" value={status} onChange={event => patch({ status: event.target.value })}>
        <option value="">Статус</option><option value="solved">Решена</option><option value="unsolved">Не решена</option>
      </select>}
      <select aria-label="Тема" value={params.get('tag') || ''} onChange={event => patch({ tag: event.target.value })}>
        <option value="">Тема</option>
        {problems.data?.tags.map(tag => <option key={tag}>{tag}</option>)}
      </select>
      {hasFilters && <button className="text-link filter-reset" onClick={() => setParams(target ? { user: target } : {})}>Сбросить</button>}
    </div>
    <QueryState query={problems}>
      {problems.data && <>{problems.data.items.length ?
        <div className="panel table-wrap">
          <table className={`data-table problem-table${personal ? '' : ' guest'}`} aria-label="Банк задач">
            <thead><tr><th aria-label="Решена" /><th><button className="text-link" onClick={() => patch({ sort: 'code' })}>Задача</button></th><th><button className="text-link" onClick={() => patch({ sort: 'difficulty' })}>Сложность</button></th><th>Решили</th>{personal && <th><button className="text-link" onClick={() => patch({ sort: 'bestTime' })}>Личный рекорд, мс</button></th>}</tr></thead>
            <tbody>{problems.data.items.map(problem => <tr key={problem.id}>
              <td>{problem.solved && <span className="accepted" aria-label="Решена">✓</span>}</td>
              <td><Link className="task-link" to={problemPath(problem.id)}>{problem.code}. {problem.title}</Link><div className="subline">{problem.tags.map((tag, index) => <span key={tag}>{index > 0 && ' / '}<button className="text-link tag" onClick={() => patch({ tag })}>{tag}</button></span>)}</div></td>
              <td><Difficulty difficulty={problem.difficulty} /></td><td>{problem.solvedCount}</td>
              {personal && <td className="mono">{problem.bestSubmissionId ? <Link to={`/submission/${encodeURIComponent(problem.bestSubmissionId)}`}><TimeValue value={problem.bestTimeMs} /></Link> : <TimeValue value={problem.bestTimeMs} />}</td>}
            </tr>)}</tbody>
          </table>
        </div> : <Empty>{hasFilters ? 'Задачи не найдены. Измените запрос или сбросьте фильтры.' : 'В банке пока нет задач.'}</Empty>}
        <Pagination total={problems.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}
