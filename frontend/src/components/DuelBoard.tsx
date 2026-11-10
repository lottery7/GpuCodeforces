import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Contest, Problem } from '../api/types';
import { ErrorMessage, TimeValue, UserLink } from '../ui';
import { solveTime } from '../utils';

export default function DuelBoard({ contest, commonProblem, finished }: { contest: Contest; commonProblem?: Problem; finished: boolean }) {
  const rows = useQuery({
    queryKey: ['standings', contest.id],
    queryFn: ({ signal }) => api.contestStandings(contest.id, signal),
    refetchInterval: finished ? false : 5000,
  });
  const opponent = useQuery({
    queryKey: ['profile', contest.opponentId],
    queryFn: ({ signal }) => api.getProfile(contest.opponentId ?? '', signal),
    enabled: Boolean(contest.opponentId),
  });
  const gpu = useQuery({
    queryKey: ['duel-gpu', contest.id, commonProblem?.id],
    queryFn: ({ signal }) => api.problemLeaders(commonProblem?.id ?? '', contest.id, signal),
    enabled: Boolean(commonProblem && commonProblem.format !== 'guess'),
    refetchInterval: finished ? false : 5000,
  });
  const people = [contest.authors.find(person => person.id === contest.challengerId), opponent.data?.user];
  const winner = rows.data?.[0];
  const draw = !winner || !winner.solved || rows.data?.filter(row => row.rank === winner.rank).length !== 1;
  return <section className="panel duel-board" aria-label="Результаты дуэли">
    <h2>{finished ? 'Результат дуэли' : 'Дуэль: лучшие результаты'}</h2>
    <ErrorMessage error={rows.error || opponent.error || gpu.error} />
    {rows.isPending && <p role="status">Загрузка результатов...</p>}
    <div className="duel-players">{[contest.challengerId, contest.opponentId].map((id, index) => {
      const row = rows.data?.find(item => item.user.id === id);
      const person = row?.user ?? people[index];
      const time = gpu.data?.find(leader => leader.user.id === id)?.timeMs;
      return <div key={id ?? index}><strong>{person ? <UserLink user={person} /> : index === 0 ? 'Инициатор' : 'Соперник'}</strong><p>{row?.solved ? <>Решена за {solveTime(row.totalSeconds)}</> : 'Нет принятого решения'}</p>{time != null && <p>Лучшее время решения: <TimeValue value={time} /> мс</p>}</div>;
    })}</div>
    {finished && rows.data && <p role="status">{draw ? 'Ничья.' : <>Победитель: <UserLink user={winner.user} />.</>}</p>}
    <p className="small muted">Место определяется решением задачи и временем первой принятой посылки. Время решения показано отдельно.{!finished && ' Результаты обновляются каждые 5 секунд.'}</p>
  </section>;
}
