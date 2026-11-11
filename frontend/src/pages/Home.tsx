import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router';
import { api } from '../api/client';
import type { Contest, User } from '../api/types';
import { useSession } from '../auth';
import { useAuthDialog } from '../auth-dialog';
import { GPU_CUDA, useProcessors } from '../processors';
import { Empty, ErrorMessage, QueryState, UserLink } from '../ui';
import { dateTime, pendingStatuses } from '../utils';
import { SubmissionTable } from './Submissions';
import { t } from '../i18n';

const limit = 8;

function countdown(value: string, now: number) {
  const seconds = Math.max(0, Math.ceil((Date.parse(value) - now) / 1000));
  const days = Math.floor(seconds / 86400);
  const time = [Math.floor(seconds % 86400 / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(part => String(part).padStart(2, '0')).join(':');
  return days ? t('{0} д {1}', [days, time]) : time;
}

function duration(contest: Contest) {
  const minutes = Math.round((Date.parse(contest.endAt) - Date.parse(contest.startAt)) / 60000);
  const hours = Math.floor(minutes / 60);
  return `${hours ? t('{0} ч', [hours]) : ''}${hours && minutes % 60 ? ' ' : ''}${minutes % 60 ? t('{0} мин', [minutes % 60]) : ''}` || t('0 мин');
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
  const catalog = useProcessors();
  const [pickedProcessor, setPickedProcessor] = useState('');
  // every processor has a rating of its own
  const ratingProcessor = pickedProcessor || catalog.processors[0]?.id || GPU_CUDA;
  // the title follows the language, so it is not set once together with the timer
  const title = t('GPU Forces - Главная');
  useEffect(() => { document.title = title; }, [title]);
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
    queryKey: ['rating', 'dashboard', ratingProcessor],
    queryFn: ({ signal }) => api.listUsers({ sort: 'rating', processor: ratingProcessor, offset: 0, limit: 100 }, signal),
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
  const ratingTitle = ownIndex >= 0 ? t('Ваш рейтинг') : t('Лучшие по рейтингу');
  const contest = upcoming.data?.[0];

  return <>
    <section className="hero">
      <svg className="hero-logo" viewBox="0 0 40 40" role="img" aria-hidden="true"><rect width="40" height="40" rx="10" fill="#3574f0" /><path d="m14 12 12 8-12 8" fill="none" stroke="#ffffff" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" /></svg>
      <div><h1 className="hero-title">GPU Forces</h1><p className="hero-sub">{t('Соревновательная платформа для программистов, где задачи требуют максимально ускорить вычисления на GPU, векторном RISC-V, NPU и собственных схемах на Verilog.')}</p></div>
    </section>
    <div className="dashboard">
      <section className="dashboard-main">
        <div className="dashboard-head"><h2>{t('Последние посылки')}</h2><Link className="text-link" to="/submissions">{t('Все посылки')}</Link></div>
        <QueryState query={latest}>
          {latest.data && (latest.data.items.length ? <SubmissionTable items={latest.data.items} showUser /> : <Empty>{t('Посылок пока нет.')}</Empty>)}
        </QueryState>
      </section>
      <aside className="dashboard-side">
        <section className="panel dashboard-contest">
          <h2>{t('Ближайший контест')}</h2>
          <QueryState query={upcoming}>
            {!contest ? <p className="muted">{t('Ближайших контестов пока нет.')}</p> : <>
              <div className="dashboard-contest-title"><Link to={`/contest/${encodeURIComponent(contest.id)}`}>{contest.title}</Link></div>
              <ul className="contest-meta">
                <li><span>{t('Начало')}</span><span>{dateTime(contest.startAt)} {t('МСК')}</span></li>
                <li><span>{t('Длительность')}</span><span>{duration(contest)}</span></li>
                <li><span>{t('Процессор')}</span><span>{catalog.title(contest.processor)}</span></li>
                <li><span>{t('Задачи')}</span><span>{contest.problemCount}</span></li>
              </ul>
              <div className="contest-countdown">{t('До начала')} <time dateTime={contest.startAt}>{countdown(contest.startAt, now)}</time></div>
              {contest.registered ? <span className="success contest-register">{t('Вы зарегистрированы')}</span>
                : user ? <button className="btn primary contest-register" disabled={join.isPending} onClick={() => join.mutate(contest.id)}>{join.isPending ? t('Регистрация...') : t('Зарегистрироваться')}</button>
                : <button className="btn primary contest-register" onClick={() => openAuth('login', location.pathname + location.search)}>{t('Зарегистрироваться')}</button>}
              {join.variables === contest.id && <ErrorMessage error={join.error} reveal />}
            </>}
          </QueryState>
        </section>
        <section className="panel">
          <div className="dashboard-head"><h2>{ratingTitle}</h2>{catalog.processors.length > 1 && <select aria-label={t('Процессор рейтинга')} value={ratingProcessor} onChange={event => setPickedProcessor(event.target.value)}>{catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select>}</div>
          <QueryState query={rating}>
            {ratingRows.length ? <div className="table-wrap"><table className="data-table" aria-label={t('Рейтинг участников')}><thead><tr><th className="num">{t('Место')}</th><th>{t('Участник')}</th><th className="num">{t('Рейтинг')}</th></tr></thead><tbody>{ratingRows.map(person => <tr key={person.id} className={person.id === user?.id ? 'own-row' : undefined}>
              <td className="num">{person.ratings[ratingProcessor]?.rank ?? '-'}</td><td><UserLink user={person} />{person.id === user?.id && <span className="small muted"> {t('вы')}</span>}</td><td className="num mono">{person.ratings[ratingProcessor]?.rating ?? '-'}</td>
            </tr>)}</tbody></table></div> : <p className="muted">{t('На процессоре')} {catalog.title(ratingProcessor)} {t('рейтингов пока нет.')}</p>}
          </QueryState>
        </section>
      </aside>
    </div>
  </>;
}