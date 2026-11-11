import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { Empty, ErrorMessage, LoginRequired, PageHeader, QueryState, RatingDelta } from '../ui';
import { pendingStatuses } from '../utils';
import { SubmissionTable } from './Submissions';

const phases = { upcoming: 'До начала', live: 'Идет', finished: 'Завершен' };

export default function Profile() {
  const [params] = useSearchParams();
  const { user, isLoading, logout } = useSession();
  const navigate = useNavigate();
  const id = params.get('user') || user?.id;
  const queryClient = useQueryClient();
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

  if (isLoading) return <p className="loading" role="status">Загрузка профиля...</p>;
  if (!id) return <><PageHeader title="Профиль" /><LoginRequired>Войдите, чтобы посмотреть свои результаты.</LoginRequired></>;
  const data = profile.data;
  const own = data?.user.id === user?.id;
  const encoded = encodeURIComponent(id);

  return <>
    <PageHeader title={data?.user.handle || 'Профиль'} actions={data && <div className="inline"><Link className="btn secondary" to={`/submissions?user=${encoded}`}>{own ? 'Мои посылки' : 'Посылки участника'}</Link>{own && <button className="btn secondary" onClick={() => void signOut()} disabled={busy}>{busy ? 'Выход...' : 'Выйти'}</button>}</div>} />
    {logoutError && <ErrorMessage error={logoutError} />}
    <QueryState query={profile}>
      {data && <>
        <div className="metrics">
          <span>Рейтинг: <strong>{data.user.rating ?? 'Нет рейтинга'}</strong>{data.user.ratingDelta !== null && data.user.ratingContestId && <> <Link className="small" to={`/contest/${encodeURIComponent(data.user.ratingContestId)}?tab=standings`}><RatingDelta value={data.user.ratingDelta} /> после последнего контеста</Link></>}</span>
          <span>Решено: <Link to={`/problems?user=${encoded}&status=solved`}>{data.solvedCount}</Link></span>
          <span>Контесты: <strong>{data.contests.length}</strong></span>
        </div>
        <section className="stack">
          <h2>Последние посылки</h2>
          <QueryState query={recent}>
            {recent.data && (recent.data.items.length ? <SubmissionTable items={recent.data.items} /> : <Empty><p>Посылок пока нет.</p>{own && <Link className="btn secondary" to="/problems">Выбрать задачу</Link>}</Empty>)}
          </QueryState>
          <h2>Соревнования</h2>
          {data.contests.length ? <div className="table-wrap"><table className="data-table" aria-label="История соревнований участника"><thead><tr><th>Соревнование</th><th>Статус</th><th className="num">Место</th><th className="num">Изменение рейтинга</th><th className="num">Рейтинг после контеста</th></tr></thead><tbody>{data.contests.map(contest => <tr key={contest.id}>
            <td><Link to={`/contest/${encodeURIComponent(contest.id)}${contest.phase === 'upcoming' ? '' : '?tab=standings'}`}>{contest.title}</Link></td>
            <td>{phases[contest.phase]}</td><td className="num">{contest.rank ?? '-'}</td><td className="num"><RatingDelta value={contest.ratingDelta} /></td><td className="num">{contest.ratingAfter ?? '-'}</td>
          </tr>)}</tbody></table></div> : <Empty>Еще не участвовал в соревнованиях.</Empty>}
        </section>
      </>}
    </QueryState>
  </>;
}
