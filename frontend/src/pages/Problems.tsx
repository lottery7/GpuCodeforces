import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { ProblemQuery } from '../api/types';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Difficulty, Empty, PageHeader, Pagination, QueryState, ScoreValue, SearchInput } from '../ui';
import { difficultyLabels, formatLabels, problemPath } from '../utils';
import { t } from '../i18n';

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
  const totalCount = problems.data?.totalCount ?? 0;
  const solvedCount = problems.data?.solvedCount ?? 0;
  const streak = problems.data?.streak ?? 0;
  const percent = totalCount ? Math.round(solvedCount / totalCount * 100) : 0;
  const cards = problems.data && <aside className="progress-stack">
    <section className="panel progress-card">
      <span className="progress-card-label">{t('Решено задач')}</span>
      <span className="progress-card-value"><strong>{solvedCount}</strong><span className="muted"> {t('из')} {totalCount}</span></span>
      <div className="progress-track" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} aria-label={t('Прогресс решения задач')}><div className="progress-fill" style={{ width: `${percent}%` }} /></div>
      <span className="progress-card-caption small muted">{percent}{t('% задач из пула')}</span>
    </section>
    <section className="panel progress-card streak-card">
      <span className="progress-card-label"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3 1.072-2.143 2.5-3.5 3.5-3.5S15 7 16 8s.5 1.5.5 2.5c0 1.38-.5 2.5-.5 2.5s1-1 1.5-2.5c.5 1.5 1 3 1 4.5A6 6 0 1 1 9 11c.5.5 1 1.5 1 2.5 0 .64-.042 1.073-.5 1z" /></svg>{t('Серия')}</span>
      <span className="progress-card-value"><strong>{streak}</strong><span className="muted"> {t('дн.')}</span></span>
      <span className="progress-card-caption small muted">{t('дней подряд с принятым решением')}{personal ? '' : t(' (войдите, чтобы считать)')}</span>
    </section>
  </aside>;
  return <>
    <PageHeader title={target ? t('Задачи участника') : t('Задачи')} />
    <div className="problems-layout">
      <div className="problems-main">
    <div className="toolbar">
      <SearchInput value={params.get('q') || ''} onChange={value => patch({ q: value })} placeholder={t('Название или номер задачи')} label={t('Найти задачу')} />
      <select aria-label={t('Процессор')} value={processor} onChange={event => {
        const value = event.target.value;
        // the best score needs a processor: without one the list goes back to the default order
        patch({ processor: value, sort: !value && sort === 'bestScore' ? undefined : params.get('sort') || undefined });
      }}>
        <option value="">{t('Все процессоры')}</option>
        {catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      <select aria-label={t('Формат задачи')} value={format} onChange={event => patch({ format: event.target.value })}>
        <option value="">{t('Все форматы')}</option>{Object.entries(formatLabels).map(([value, title]) => <option key={value} value={value}>{title}</option>)}
      </select>
      <select aria-label={t('Сложность')} value={difficulty} onChange={event => patch({ difficulty: event.target.value })}>
        <option value="">{t('Сложность')}</option>
        {Object.entries(difficultyLabels).map(([value, title]) => <option key={value} value={value}>{title}</option>)}
      </select>
      {personal && <select aria-label={t('Статус решения')} value={status} onChange={event => patch({ status: event.target.value })}>
        <option value="">{t('Статус')}</option><option value="solved">{t('Решена')}</option><option value="unsolved">{t('Не решена')}</option>
      </select>}
      <select aria-label={t('Тема')} value={params.get('tag') || ''} onChange={event => patch({ tag: event.target.value })}>
        <option value="">{t('Тема')}</option>
        {problems.data?.tags.map(tag => <option key={tag}>{tag}</option>)}
      </select>
      {hasFilters && <button className="text-link filter-reset" onClick={() => setParams(target ? { user: target } : {})}>{t('Сбросить')}</button>}
    </div>
    <QueryState query={problems}>
      {problems.data && <>{problems.data.items.length ?
        <div className="panel table-wrap">
          <table className={`data-table problem-table${personal ? '' : ' guest'}`} aria-label={t('Банк задач')}>
            <thead><tr><th aria-label={t('Решена')} /><th><button className="text-link" onClick={() => patch({ sort: 'code' })}>{t('Задача')}</button></th><th><button className="text-link" onClick={() => patch({ sort: 'difficulty' })}>{t('Сложность')}</button></th><th className="num">{t('Решили')}</th>{scores && <th className="num">{t('Рекорд')}</th>}{scores && personal && <th className="num"><button className="text-link" onClick={() => patch({ sort: 'bestScore' })}>{t('Личный рекорд')}</button></th>}</tr></thead>
            <tbody>{problems.data.items.map(problem => {
              const onProcessor = problem.processors.find(item => item.id === processor);
              const guess = problem.format === 'guess';
              return <tr key={problem.id}>
                <td>{problem.solved && <span className="accepted" aria-label={t('Решена')}>✓</span>}</td>
                <td><Link className="task-link" to={problemPath(problem.id, 'practice', undefined, onProcessor ? { processor: onProcessor.id, language: onProcessor.languages[0] ?? '' } : undefined)}>{problem.code}. {problem.title}</Link><div className="subline">{problem.tags.map((tag, index) => <span key={tag}>{index > 0 && ' / '}<button className="text-link tag" onClick={() => patch({ tag })}>{tag}</button></span>)}{!processor && problem.processors.length > 0 && <span className="muted"> · {problem.processors.map(item => catalog.title(item.id)).join(', ')}</span>}</div></td>
                <td><Difficulty difficulty={problem.difficulty} /><div className="subline">{formatLabels[problem.format ?? 'standard']}</div></td><td className="num">{problem.solvedCount}</td>
                {scores && <td className="num">{guess ? '-' : <ScoreValue score={onProcessor?.recordScore} />}</td>}
                {scores && personal && <td className="num mono">{guess ? problem.solved ? '+' : '-' : onProcessor?.bestSubmissionId ? <Link to={`/submission/${encodeURIComponent(onProcessor.bestSubmissionId)}`}><ScoreValue score={onProcessor.bestScore} /></Link> : <ScoreValue score={onProcessor?.bestScore} />}</td>}
              </tr>;
            })}</tbody>
          </table>
        </div> : <Empty>{hasFilters ? t('Задачи не найдены. Измените запрос или сбросьте фильтры.') : t('В банке пока нет задач.')}</Empty>}
        <Pagination total={problems.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
      </div>
      {cards}
    </div>
  </>;
}
