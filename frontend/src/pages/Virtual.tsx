import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { LoginRequired, PageHeader, QueryState } from '../ui';
import { dateTime, problemPath, solveTime } from '../utils';
import { t } from '../i18n';

export default function Virtual() {
  const { id } = useParams();
  const { user } = useSession();
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const attempt = useQuery({ queryKey: ['virtual', id, user?.id], queryFn: ({ signal }) => api.getVirtual(id!, signal), enabled: Boolean(id && user), refetchInterval: 5000 });
  const history = useQuery({ queryKey: ['virtual-history', user?.id], queryFn: ({ signal }) => api.listVirtual(signal), enabled: Boolean(!id && user), refetchInterval: 15000 });
  const context = `virtual:${id}`;
  const contestId = attempt.data?.contestId;
  const contest = useQuery({ queryKey: ['contest', contestId, user?.id], queryFn: ({ signal }) => api.getContest(contestId!, signal), enabled: Boolean(contestId) });
  const tasks = useQuery({ queryKey: ['virtual-tasks', id, user?.id], queryFn: ({ signal }) => api.allProblems({ context }, signal), enabled: Boolean(id && attempt.data), refetchInterval: 5000 });
  if (!user) return <LoginRequired returnTo={id ? `/virtual/${id}` : '/virtual'}>{t('Войдите для виртуального участия.')}</LoginRequired>;
  if (!id) return <><PageHeader title={t('Мои виртуальные контесты')} /><QueryState query={history}>{history.data?.length ? <ul>{history.data.map(item => <li key={item.id}><Link to={`/virtual/${item.id}`}>{dateTime(item.startedAt)}</Link> {t('· Решено:')} {item.solved} {t('· Время:')} {solveTime(item.totalSeconds)}</li>)}</ul> : <p>{t('Попыток пока нет. Начать можно со страницы завершённого контеста.')}</p>}</QueryState></>;
  const item = attempt.data;
  const serverNow = item ? now + Date.parse(item.serverNow) - attempt.dataUpdatedAt : now;
  const remaining = item ? Math.max(0, Math.ceil((Date.parse(item.endsAt) - serverNow) / 1000)) : 0;
  return <><PageHeader title={contest.data ? t('Виртуально: {0}', [contest.data.title]) : t('Виртуальный контест')} actions={<Link to="/virtual">{t('Все попытки')}</Link>} /><QueryState query={attempt}>{item && <>
    <p className="notice" role="status">{remaining > 0 ? t('Осталось {0}:{1}:{2}', [Math.floor(remaining / 3600), String(Math.floor(remaining / 60) % 60).padStart(2, '0'), String(remaining % 60).padStart(2, '0')]) : item.pending ? t('Время истекло. Ожидаем проверки отправленных решений.') : t('Виртуальный контест завершён.')}</p>
    <p>{t('Решено:')} {item.solved}{t('. Время сдачи:')} {solveTime(item.totalSeconds)}{t('. Участие не изменяет рейтинг.')}</p>
    <QueryState query={tasks}><ul>{tasks.data?.map((problem, index) => <li key={problem.id}><Link to={problemPath(problem.id, remaining > 0 ? context : `upsolve:${item.contestId}`)}>{String.fromCharCode(65 + index)}. {problem.title}</Link> · {item.times[problem.id] === undefined ? t('не решена') : solveTime(item.times[problem.id])}</li>)}</ul></QueryState>
    {remaining === 0 && <p><Link to={`/contest/${item.contestId}?tab=standings`}>{t('Сравнить с официальной таблицей')}</Link> · <Link to={`/contest/${item.contestId}?mode=upsolving`}>{t('Дорешивание')}</Link></p>}
  </>}</QueryState></>;
}
