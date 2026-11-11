import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { ProblemQuery } from '../api/types';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Empty, PageHeader, Pagination, QueryState, ScoreValue, SearchInput } from '../ui';
import { difficultyLabels, formatLabels, problemPath } from '../utils';

const limit = 20;

export default function Problems() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const target = params.get('user') || undefined;
  const difficulty = params.get('difficulty') || '';
  const sort = params.get('sort') || 'code';
  const status = params.get('status') || '';
  const processor = params.get('processor') || '';
  const format = params.get('format') || '';
  const catalog = useProcessors();
  const offset = Math.max(0, Number.parseInt(params.get('offset') || '0', 10) || 0);
  const query: ProblemQuery = {
    context: 'practice',
    format: format === 'standard' || format === 'optimization' || format === 'guess' ? format : undefined,
    q: params.get('q') || undefined,
    difficulty: difficulty === 'easy' || difficulty === 'medium' || difficulty === 'hard' ? difficulty : undefined,
    tag: params.get('tag') || undefined,
    solved: status === 'solved' ? true : status === 'unsolved' ? false : undefined,
    processor: processor || undefined,
    // the best score is the best on a processor: without one the list cannot be sorted by it
    sort: sort === 'difficulty' || sort === 'bestScore' && processor ? sort : 'code',
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
  const hasFilters = Boolean(params.get('q') || difficulty || params.get('tag') || status || processor || format);
  const scores = Boolean(processor);
  return <>
    <PageHeader title={target ? 'Задачи участника' : 'Задачи'} />
    <div className="toolbar">
      <SearchInput value={params.get('q') || ''} onChange={value => patch({ q: value })} placeholder="Название или номер задачи" label="Найти задачу" />
      <select aria-label="Процессор" value={processor} onChange={event => {
        const value = event.target.value;
        // the best score needs a processor: without one the list goes back to the default order
        patch({ processor: value, sort: !value && sort === 'bestScore' ? undefined : params.get('sort') || undefined });
      }}>
        <option value="">Все процессоры</option>
        {catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      <select aria-label="Формат задачи" value={format} onChange={event => patch({ format: event.target.value })}>
        <option value="">Все форматы</option>{Object.entries(formatLabels).map(([value, title]) => <option key={value} value={value}>{title}</option>)}
      </select>
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
            <thead><tr><th aria-label="Решена" /><th><button className="text-link" onClick={() => patch({ sort: 'code' })}>Задача</button></th><th><button className="text-link" onClick={() => patch({ sort: 'difficulty' })}>Сложность</button></th><th>Решили</th>{scores && <th>Рекорд</th>}{scores && personal && <th><button className="text-link" onClick={() => patch({ sort: 'bestScore' })}>Личный рекорд</button></th>}</tr></thead>
            <tbody>{problems.data.items.map(problem => {
              const onProcessor = problem.processors.find(item => item.id === processor);
              const guess = problem.format === 'guess';
              return <tr key={problem.id}>
                <td>{problem.solved && <span className="accepted" aria-label="Решена">✓</span>}</td>
                <td><Link className="task-link" to={problemPath(problem.id, 'practice', undefined, onProcessor ? { processor: onProcessor.id, language: onProcessor.languages[0] ?? '' } : undefined)}>{problem.code}. {problem.title}</Link><div className="subline">{problem.tags.map((tag, index) => <span key={tag}>{index > 0 && ' / '}<button className="text-link tag" onClick={() => patch({ tag })}>{tag}</button></span>)}{!processor && problem.processors.length > 0 && <span className="muted"> · {problem.processors.map(item => catalog.title(item.id)).join(', ')}</span>}</div></td>
                <td>{difficultyLabels[problem.difficulty]}<div className="subline">{formatLabels[problem.format ?? 'standard']}</div></td><td>{problem.solvedCount}</td>
                {scores && <td>{guess ? '-' : <ScoreValue score={onProcessor?.recordScore} />}</td>}
                {scores && personal && <td className="mono">{guess ? problem.solved ? '+' : '-' : onProcessor?.bestSubmissionId ? <Link to={`/submission/${encodeURIComponent(onProcessor.bestSubmissionId)}`}><ScoreValue score={onProcessor.bestScore} /></Link> : <ScoreValue score={onProcessor?.bestScore} />}</td>}
              </tr>;
            })}</tbody>
          </table>
        </div> : <Empty>{hasFilters ? 'Задачи не найдены. Измените запрос или сбросьте фильтры.' : 'В банке пока нет задач.'}</Empty>}
        <Pagination total={problems.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}
