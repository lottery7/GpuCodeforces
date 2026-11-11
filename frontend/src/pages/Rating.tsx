import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { GPU_CUDA, useProcessors } from '../processors';
import { Empty, PageHeader, Pagination, QueryState, SearchInput, UserLink } from '../ui';

const limit = 20;
const signed = (value: number | null) => value === null ? '-' : `${value > 0 ? '+' : ''}${value}`;

export default function Rating() {
  const [params, setParams] = useSearchParams();
  const { user } = useSession();
  const catalog = useProcessors();
  // every processor has a rating of its own
  const processor = params.get('processor') || catalog.processors[0]?.id || GPU_CUDA;
  const q = params.get('q') || '';
  const requested = Number(params.get('offset') || 0);
  const offset = Number.isSafeInteger(requested) && requested >= 0 ? Math.floor(requested / limit) * limit : 0;
  const rating = useQuery({
    queryKey: ['rating', processor, q.trim(), offset],
    queryFn: ({ signal }) => api.listUsers({ sort: 'rating', processor, q: q.trim() || undefined, offset, limit }, signal),
  });
  useEffect(() => {
    if (!rating.data || rating.error || offset === 0 || offset < rating.data.total) return;
    const next = new URLSearchParams(params);
    const last = Math.max(0, Math.ceil(rating.data.total / limit) - 1) * limit;
    last ? next.set('offset', String(last)) : next.delete('offset');
    setParams(next, { replace: true });
  }, [rating.data, rating.error, offset, params, setParams]);

  function search(value: string) {
    const next = new URLSearchParams();
    if (params.get('processor')) next.set('processor', processor);
    if (value) next.set('q', value);
    setParams(next, { replace: true });
  }

  function pick(value: string) {
    const next = new URLSearchParams();
    next.set('processor', value);
    if (q) next.set('q', q);
    setParams(next, { replace: true });
  }

  return <>
    <PageHeader title="Рейтинг участников" description="У каждого процессора свой рейтинг: его меняют только контесты на этом процессоре." />
    <div className="toolbar rating-search">
      <select aria-label="Процессор рейтинга" value={processor} onChange={event => pick(event.target.value)}>
        {(catalog.processors.length ? catalog.processors : [{ id: processor, title: processor }]).map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      <SearchInput value={q} onChange={search} placeholder="Ник участника" label="Найти участника" />
      {q && <button className="text-link filter-reset" type="button" onClick={() => search('')}>Сбросить</button>}
    </div>
    <QueryState query={rating}>
      {rating.data && <>
        {rating.data.items.length ? <div className="table-wrap"><table className="data-table" aria-label="Рейтинг участников"><thead><tr><th>Место</th><th>Участник</th><th>Рейтинг</th><th>После последнего контеста</th></tr></thead><tbody>{rating.data.items.map(person => {
          const own = person.ratings[processor];
          return <tr key={person.id} className={person.id === user?.id ? 'own-row' : undefined}>
            <td>{own?.rank ?? '-'}</td><td><UserLink user={person} />{person.id === user?.id && <span className="small muted"> вы</span>}</td><td>{own?.rating ?? '-'}</td><td>{own?.ratingContestId && own.ratingDelta !== null ? <Link to={`/contest/${encodeURIComponent(own.ratingContestId)}?tab=standings`}>{signed(own.ratingDelta)}</Link> : signed(own?.ratingDelta ?? null)}</td>
          </tr>;
        })}</tbody></table></div> : <Empty>{q.trim() ? 'Участники не найдены. Измените запрос.' : `Участников с рейтингом на процессоре ${catalog.title(processor)} пока нет.`}</Empty>}
        <Pagination total={rating.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}
