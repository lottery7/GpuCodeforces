import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { Empty, ErrorMessage, LoginRequired, PageHeader, QueryState, RatingDelta } from '../ui';
import { pendingStatuses } from '../utils';
import { SubmissionTable } from './Submissions';
import { t, translated } from '../i18n';

const phases = translated({ upcoming: 'До начала', live: 'Идет', finished: 'Завершен' });

export default function Profile() {
  const [params] = useSearchParams();
  const { user, isLoading, logout } = useSession();
  const navigate = useNavigate();
  const id = params.get('user') || user?.id;
  const queryClient = useQueryClient();
  const catalog = useProcessors();
  const previous = useRef(new Map<string, string>());
  const [busy, setBusy] = useState(false);
  const [logoutError, setLogoutError] = useState<unknown>(null);
  async function signOut() {
    if (busy) return;
    setBusy(true);
    setLogoutError(null);
    try { await logout(); navigate('/problems'); }
    catch (error) { setLogoutError(error); }
    finally { setBusy(false); }
  }
  const profile = useQuery({
    queryKey: ['profile', id],
    queryFn: ({ signal }) => api.getProfile(id!, signal),
    enabled: Boolean(id) && !isLoading,
  });
  const recent = useQuery({
    queryKey: ['profile-submissions', id, user?.id],
    queryFn: ({ signal }) => api.listSubmissions({ userId: id, offset: 0, limit: 5 }, signal),
    enabled: Boolean(id) && !isLoading,
    refetchInterval: query => query.state.data?.items.some(item => pendingStatuses.has(item.status)) ? 2500 : false,
  });
  useEffect(() => { previous.current.clear(); }, [id, user?.id]);
  useEffect(() => {
    const items = recent.data?.items;
    if (!items) return;
    if (items.some(item => pendingStatuses.has(previous.current.get(item.id) || '') && !pendingStatuses.has(item.status))) {
      void queryClient.invalidateQueries({ queryKey: ['profile', id] });
    }
    previous.current = new Map(items.map(item => [item.id, item.status]));
  }, [id, recent.data, queryClient]);

  if (isLoading) return <p className="loading" role="status">{t('Загрузка профиля...')}</p>;
  if (!id) return <><PageHeader title={t('Профиль')} /><LoginRequired>{t('Войдите, чтобы посмотреть свои результаты.')}</LoginRequired></>;
  const data = profile.data;
  const own = data?.user.id === user?.id;
  const encoded = encodeURIComponent(id);

  return <>
    <PageHeader title={data?.user.handle || t('Профиль')} actions={data && <div className="inline"><Link className="btn secondary" to={`/submissions?user=${encoded}`}>{own ? t('Мои посылки') : t('Посылки участника')}</Link>{own && <button className="btn secondary" onClick={() => void signOut()} disabled={busy}>{busy ? t('Выход...') : t('Выйти')}</button>}</div>} />
    {logoutError && <ErrorMessage error={logoutError} />}
    <QueryState query={profile}>
      {data && <>
        <div className="metrics">
          {Object.keys(data.user.ratings).length ? Object.entries(data.user.ratings).map(([processor, rating]) => <span key={processor}>{t('Рейтинг (')}{catalog.title(processor)}): <Link to={`/rating?processor=${encodeURIComponent(processor)}`}><strong>{rating.rating}</strong></Link>{rating.ratingDelta !== null && rating.ratingContestId && <> <Link className="small" to={`/contest/${encodeURIComponent(rating.ratingContestId)}?tab=standings`}><RatingDelta value={rating.ratingDelta} /> {t('после последнего контеста')}</Link></>}</span>)
            : <span>{t('Рейтинг:')} <strong>{t('Нет рейтинга')}</strong></span>}
          <span>{t('Решено:')} <Link to={`/problems?user=${encoded}&status=solved`}>{data.solvedCount}</Link></span>
          <span>{t('Контесты:')} <strong>{data.contests.length}</strong></span>
        </div>
        <section className="stack">
          {data.user.id === user?.id && <p><Link to="/virtual">{t('Мои виртуальные контесты')}</Link></p>}
          <h2>{t('Последние посылки')}</h2>
          <QueryState query={recent}>
            {recent.data && (recent.data.items.length ? <SubmissionTable items={recent.data.items} /> : <Empty><p>{t('Посылок пока нет.')}</p>{own && <Link className="btn secondary" to="/problems">{t('Выбрать задачу')}</Link>}</Empty>)}
          </QueryState>
          <h2>{t('Соревнования')}</h2>
          {data.contests.length ? <div className="table-wrap"><table className="data-table" aria-label={t('История соревнований участника')}><thead><tr><th>{t('Соревнование')}</th><th>{t('Процессор')}</th><th>{t('Статус')}</th><th className="num">{t('Место')}</th><th className="num">{t('Изменение рейтинга')}</th><th className="num">{t('Рейтинг после контеста')}</th></tr></thead><tbody>{data.contests.map(contest => <tr key={contest.id}>
            <td><Link to={`/contest/${encodeURIComponent(contest.id)}${contest.phase === 'upcoming' ? '' : '?tab=standings'}`}>{contest.title}</Link></td>
            <td>{catalog.title(contest.processor)}</td><td>{phases[contest.phase]}</td><td className="num">{contest.rank ?? '-'}</td><td className="num"><RatingDelta value={contest.ratingDelta} /></td><td className="num">{contest.ratingAfter ?? '-'}</td>
          </tr>)}</tbody></table></div> : <Empty>{t('Еще не участвовал в соревнованиях.')}</Empty>}
        </section>
      </>}
    </QueryState>
  </>;
}
