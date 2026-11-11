import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { GPU_CUDA, useProcessors } from '../processors';
import { Empty, PageHeader, Pagination, QueryState, RatingDelta, SearchInput, UserLink } from '../ui';
import { t } from '../i18n';

const limit = 20;

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
    <PageHeader title={t('Рейтинг участников')} description={t('У каждого процессора свой рейтинг: его меняют только контесты на этом процессоре.')} />
    <div className="toolbar rating-search">
      <select aria-label={t('Процессор рейтинга')} value={processor} onChange={event => pick(event.target.value)}>
        {(catalog.processors.length ? catalog.processors : [{ id: processor, title: processor }]).map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      <SearchInput value={q} onChange={search} placeholder={t('Ник участника')} label={t('Найти участника')} />
      {q && <button className="text-link filter-reset" type="button" onClick={() => search('')}>{t('Сбросить')}</button>}
    </div>
    <QueryState query={rating}>
      {rating.data && <>
        {rating.data.items.length ? <div className="table-wrap"><table className="data-table" aria-label={t('Рейтинг участников')}><thead><tr><th className="num">{t('Место')}</th><th>{t('Участник')}</th><th className="num">{t('Рейтинг')}</th><th className="num">{t('После последнего контеста')}</th></tr></thead><tbody>{rating.data.items.map(person => {
          const own = person.ratings[processor];
          return <tr key={person.id} className={person.id === user?.id ? 'own-row' : undefined}>
            <td className="num">{own?.rank ?? '-'}</td><td><UserLink user={person} />{person.id === user?.id && <span className="small muted"> {t('вы')}</span>}</td><td className="num">{own?.rating ?? '-'}</td><td className="num">{own?.ratingContestId && own.ratingDelta !== null ? <Link to={`/contest/${encodeURIComponent(own.ratingContestId)}?tab=standings`}><RatingDelta value={own.ratingDelta} /></Link> : <RatingDelta value={own?.ratingDelta} />}</td>
          </tr>;
        })}</tbody></table></div> : <Empty>{q.trim() ? t('Участники не найдены. Измените запрос.') : t('Участников с рейтингом на процессоре {0} пока нет.', [catalog.title(processor)])}</Empty>}
        <Pagination total={rating.data.total} offset={offset} limit={limit} onChange={value => { const next = new URLSearchParams(params); value ? next.set('offset', String(value)) : next.delete('offset'); setParams(next); }} />
      </>}
    </QueryState>
  </>;
}
