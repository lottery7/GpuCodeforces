import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router';
import { api } from '../api/client';
import type { Contest, User } from '../api/types';
import { useSession } from '../auth';
import { useAuthDialog } from '../auth-dialog';
import { Empty, ErrorMessage, PageHeader, QueryState, UserLink } from '../ui';
import { dateTime, pendingStatuses } from '../utils';
import { SubmissionTable } from './Submissions';

const limit = 8;

function countdown(value: string, now: number) {
  const seconds = Math.max(0, Math.ceil((Date.parse(value) - now) / 1000));
  const days = Math.floor(seconds / 86400);
  const time = [Math.floor(seconds % 86400 / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(part => String(part).padStart(2, '0')).join(':');
  return days ? `${days} д ${time}` : time;
}

function duration(contest: Contest) {
  const minutes = Math.round((Date.parse(contest.endAt) - Date.parse(contest.startAt)) / 60000);
  const hours = Math.floor(minutes / 60);
  return `${hours ? `${hours} ч` : ''}${hours && minutes % 60 ? ' ' : ''}${minutes % 60 ? `${minutes % 60} мин` : ''}` || '0 мин';
}

function windowAround(items: User[], ownIndex: number): User[] {
  const start = Math.max(0, Math.min(ownIndex - 2, items.length - 5));
  return items.slice(start, start + 5);
}

export default function Home() {
  const { user, isLoading } = useSession();
  const openAuth = useAuthDialog();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const latest = useQuery({
    queryKey: ['latest-submissions'],
    queryFn: ({ signal }) => api.listLatestSubmissions({ offset: 0, limit }, signal),
    enabled: !isLoading,
    refetchInterval: query => query.state.data?.items.some(item => pendingStatuses.has(item.status)) ? 2500 : false,
  });
  const upcoming = useQuery({
    queryKey: ['contests', 'upcoming', 'dashboard'],
    queryFn: ({ signal }) => api.listContests({ phase: 'upcoming' }, signal),
    refetchInterval: 5000,
  });
  const rating = useQuery({
    queryKey: ['rating', 'dashboard'],
    queryFn: ({ signal }) => api.listUsers({ sort: 'rating', offset: 0, limit: 100 }, signal),
  });
  const join = useMutation({
    mutationFn: (id: string) => api.registerForContest(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['contests'] });
      void queryClient.invalidateQueries({ queryKey: ['contest'] });
      void queryClient.invalidateQueries({ queryKey: ['profile'] });
      void queryClient.invalidateQueries({ queryKey: ['rating'] });
    },
  });

  const people = rating.data?.items ?? [];
  const ownIndex = user ? people.findIndex(person => person.id === user.id) : -1;
  const ratingRows = ownIndex >= 0 ? windowAround(people, ownIndex) : people.slice(0, 5);
  const ratingTitle = ownIndex >= 0 ? 'Ваш рейтинг' : 'Лучшие по рейтингу';
  const contest = upcoming.data?.[0];

  return <>
    <PageHeader title="Главная" />
    <div className="dashboard">
      <section className="dashboard-main">
        <div className="dashboard-head"><h2>Последние посылки</h2><Link className="text-link" to="/submissions">Все посылки</Link></div>
        <QueryState query={latest}>
          {latest.data && (latest.data.items.length ? <SubmissionTable items={latest.data.items} showUser /> : <Empty>Посылок пока нет.</Empty>)}
        </QueryState>
      </section>
      <aside className="dashboard-side">
        <section className="panel dashboard-contest">
          <h2>Ближайший контест</h2>
          <QueryState query={upcoming}>
            {!contest ? <p className="muted">Ближайших контестов пока нет.</p> : <>
              <div className="dashboard-contest-title"><Link to={`/contest/${encodeURIComponent(contest.id)}`}>{contest.title}</Link></div>
              <ul className="contest-meta">
                <li><span>Начало</span><span>{dateTime(contest.startAt)} МСК</span></li>
                <li><span>Длительность</span><span>{duration(contest)}</span></li>
                <li><span>Задачи</span><span>{contest.problemCount}</span></li>
              </ul>
              <div className="contest-countdown">До начала <time dateTime={contest.startAt}>{countdown(contest.startAt, now)}</time></div>
              {contest.registered ? <span className="success contest-register">Вы зарегистрированы</span>
                : user ? <button className="btn primary contest-register" disabled={join.isPending} onClick={() => join.mutate(contest.id)}>{join.isPending ? 'Регистрация...' : 'Зарегистрироваться'}</button>
                : <button className="btn primary contest-register" onClick={() => openAuth('login', location.pathname + location.search)}>Зарегистрироваться</button>}
              {join.variables === contest.id && <ErrorMessage error={join.error} reveal />}
            </>}
          </QueryState>
        </section>
        <section className="panel">
          <h2>{ratingTitle}</h2>
          <QueryState query={rating}>
            {ratingRows.length ? <div className="table-wrap"><table className="data-table" aria-label="Рейтинг участников"><thead><tr><th>Место</th><th>Участник</th><th>Рейтинг</th></tr></thead><tbody>{ratingRows.map(person => <tr key={person.id} className={person.id === user?.id ? 'own-row' : undefined}>
              <td>{person.rank ?? '-'}</td><td><UserLink user={person} />{person.id === user?.id && <span className="small muted"> вы</span>}</td><td>{person.rating ?? '-'}</td>
            </tr>)}</tbody></table></div> : <p className="muted">Рейтингов пока нет.</p>}
          </QueryState>
        </section>
      </aside>
    </div>
  </>;
}