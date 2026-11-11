import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { Empty, PageHeader, Pagination, QueryState, RatingDelta, SearchInput, UserLink } from '../ui';

const limit = 20;

export default function Rating() {
  const [params, setParams] = useSearchParams();
  const { user } = useSession();
  const q = params.get('q') || '';
  const requested = Number(params.get('offset') || 0);
  const offset = Number.isSafeInteger(requested) && requested >= 0 ? Math.floor(requested / limit) * limit : 0;
  const rating = useQuery({
    queryKey: ['rating', q.trim(), offset],
    queryFn: ({ signal }) => api.listUsers({ sort: 'rating', q: q.trim() || undefined, offset, limit }, signal),
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
    if (value) next.set('q', value);
    setParams(next, { replace: true });
  }

  return <>
    <PageHeader title="Рейтинг участников" />
    <div className="toolbar rating-search">
      <SearchInput value={q} onChange={search} placeholder="Ник участника" label="Найти участника" />
      {q && <button className="text-link filter-reset" type="button" onClick={() => search('')}>Сбросить</button>}
    </div>
    <QueryState query={rating}>
      {rating.data && <>
        {rating.data.items.length ? <div className="table-wrap"><table className="data-table" aria-label="Рейтинг участников"><thead><tr><th>Место</th><th>Участник</th><th>Рейтинг</th><th>После последнего контеста</th></tr></thead><tbody>{rating.data.items.map(person => <tr key={person.id} className={person.id === user?.id ? 'own-row' : undefined}>
          <td>{person.rank ?? '-'}</td><td><UserLink user={person} />{person.id === user?.id && <span className="small muted"> вы</span>}</td><td>{person.rating ?? '-'}</td><td>{person.ratingContestId && person.ratingDelta !== null ? <Link to={`/contest/${encodeURIComponent(person.ratingContestId)}?tab=standings`}><RatingDelta value={person.ratingDelta} /></Link> : <RatingDelta value={person.ratingDelta} />}</td>
        </tr>)}</tbody></table></div> : <Empty>{q.trim() ? 'Участники не найдены. Измените запрос.' : 'Участников с рейтингом пока нет.'}</Empty>}
        <Pagination total={rating.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}
