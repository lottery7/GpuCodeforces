import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Contest, Standing } from '../api/types';
import { useSession } from '../auth';
import { useProcessors } from '../processors';
import { ErrorMessage, ScoreValue, UserLink } from '../ui';
import { solveTime } from '../utils';
import { t } from '../i18n';

export default function DuelBoard({ contest, finished, contestUpdatedAt, clockOffset = 0, showHeading = true }: { contest: Contest; finished: boolean; contestUpdatedAt?: number; clockOffset?: number; showHeading?: boolean }) {
  const { user } = useSession();
  const processors = useProcessors();
  const cache = useQueryClient();
  const [localNow, setNow] = useState(Date.now);
  const now = localNow + clockOffset;
  const [confirm, setConfirm] = useState(false);
  const [surrendered, setSurrendered] = useState<string | null>(null);
  const [finalized, setFinalized] = useState<string | null>(null);
  const ended = finished || surrendered === contest.id || Boolean(contest.surrenderedUserId) || Date.parse(contest.endAt) <= now;
  const pending = ended && contest.resultsPending && !contest.surrenderedUserId && surrendered !== contest.id;
  const snapshot = ended && !pending && (contest.surrenderedUserId || contestUpdatedAt == null || contestUpdatedAt >= Date.parse(contest.endAt)) ? `${contest.id}:${contest.endAt}:${contest.surrenderedUserId ?? ''}:${contestUpdatedAt ?? ''}` : null;
  const finalReady = snapshot !== null && finalized === snapshot;
  useEffect(() => setConfirm(false), [contest.id]);
  useEffect(() => { if (ended) return; const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, [ended]);
  const rows = useQuery({ queryKey: ['standings', contest.id], queryFn: ({ signal }) => api.contestStandings(contest.id, signal), refetchInterval: !ended || pending ? 5000 : false });
  const refetchRows = rows.refetch;
  useEffect(() => {
    if (!snapshot) { setFinalized(null); return; }
    let current = true;
    void refetchRows().then(result => { if (current && !result.isError) setFinalized(snapshot); });
    return () => { current = false; };
  }, [snapshot, refetchRows]);
  const surrender = useMutation({
    mutationFn: (id: string) => api.surrenderContest(id),
    onSuccess: async (_, id) => {
      setSurrendered(id);
      setConfirm(false);
      cache.setQueriesData<Contest>({ queryKey: ['contest', id] }, value => value ? { ...value, endAt: new Date(Date.now() + clockOffset).toISOString(), surrenderedUserId: user?.id, resultsPending: false } : value);
      cache.setQueryData<Standing[]>(['standings', id], value => value?.map(row => ({ ...row, rank: row.user.id === user?.id ? 2 : 1, surrendered: row.user.id === user?.id })).sort((a, b) => a.rank - b.rank));
      await Promise.all([cache.invalidateQueries({ queryKey: ['contest', id] }), cache.invalidateQueries({ queryKey: ['standings', id] }), cache.invalidateQueries({ queryKey: ['duels'] })]);
    },
  });
  const winner = rows.data?.[0];
  const draw = !winner || rows.data?.filter(row => row.rank === winner.rank).length !== 1;
  const participant = user && [contest.challengerId, contest.opponentId].includes(user.id);
  return <section className="panel duel-board" aria-label={t('Результаты дуэли')}>
    <div className="duel-board-heading">{showHeading && <h2>{ended ? t('Результат дуэли') : t('Дуэль')}</h2>}<span className="small muted">{processors.title(contest.processor)}</span>
      {!ended && <time dateTime={contest.endAt}>{t('До конца:')} {solveTime(Math.max(0, Math.ceil((Date.parse(contest.endAt) - now) / 1000)))}</time>}
      {participant && !ended && Date.parse(contest.startAt) <= now && !confirm && <button className="text-link duel-surrender" onClick={() => setConfirm(true)}>{t('Сдаться')}</button>}
    </div>
    {confirm && !ended && <div className="duel-surrender-confirm" role="group" aria-label={t('Подтвердить сдачу')}><span>{t('Завершить дуэль поражением?')}</span><button className="btn secondary" disabled={surrender.isPending} onClick={() => surrender.mutate(contest.id)}>{surrender.isPending ? t('Завершаем...') : t('Да, сдаться')}</button><button className="text-link" disabled={surrender.isPending} onClick={() => setConfirm(false)}>{t('Отмена')}</button></div>}
    <ErrorMessage error={rows.error || surrender.error} />
    {rows.isPending && !ended && <p role="status">{t('Загрузка результатов...')}</p>}
    <div className="duel-players">{rows.data?.map(row => <div key={row.user.id} className={row.surrendered ? 'surrendered' : undefined}>
      <div className="duel-player-heading"><span className="duel-place">{row.rank}</span><UserLink user={row.user} /></div>
      <p>{row.bestScore ? <ScoreValue score={row.bestScore} /> : t('Нет принятого решения')}</p>
      {row.surrendered && <span className="small muted">{t('Сдался')}</span>}
    </div>)}</div>
    {pending ? <p role="status">{t('Проверяются посылки')}</p> : ended && !finalReady ? !rows.error && <p role="status">{t('Загрузка результатов...')}</p> : ended && rows.data && <p role="status">{draw ? t('Ничья.') : <>{t('Победитель:')} <UserLink user={winner.user} />.</>}</p>}
    {ended && !pending && rows.error && <button className="text-link" onClick={() => void refetchRows().then(result => { if (snapshot && !result.isError) setFinalized(snapshot); })}>{t('Повторить загрузку результатов')}</button>}
  </section>;
}
