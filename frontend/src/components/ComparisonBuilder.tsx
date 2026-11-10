import { useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { ErrorMessage } from '../ui';
import { phaseOf, problemPath } from '../utils';

export default function ComparisonBuilder({ problemId }: { problemId: string }) {
  const { user } = useSession();
  const navigate = useNavigate();
  const [opened, setOpened] = useState(false);
  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  const [explanation, setExplanation] = useState('');
  const submissions = useQuery({
    queryKey: ['comparison-candidates', problemId, user?.id],
    queryFn: ({ signal }) => api.listSubmissions({ problemId, userId: user?.id, context: 'all', status: 'accepted', limit: 100 }, signal),
    enabled: opened && Boolean(user),
  });
  const create = useMutation({
    mutationFn: () => api.createComparison(problemId, { firstSubmissionId: first, secondSubmissionId: second, explanation: explanation.trim() }),
    onSuccess: problem => navigate(problemPath(problem.id)),
  });
  const contests = useQuery({ queryKey: ['comparison-contests', user?.id], queryFn: ({ signal }) => api.listContests({}, signal), enabled: opened && Boolean(user) });
  if (!user) return null;
  const candidates = submissions.data?.items.filter(item => item.format !== 'guess' && item.timeMs != null && item.timeMs > 0 && (!item.contestId || contests.data?.some(contest => contest.id === item.contestId && phaseOf(contest) === 'finished'))) ?? [];
  function submit(event: FormEvent) {
    event.preventDefault();
    if (first && second && first !== second && explanation.trim() && !create.isPending) create.mutate();
  }
  return <details className="disclosure comparison-builder" onToggle={event => setOpened(event.currentTarget.open)}>
    <summary>Сравнить мои решения</summary>
    <p>Создайте задачу "Угадай, какой код быстрее" из двух своих принятых посылок в одной конфигурации проверки.</p>
    <p className="notice">Оба выбранных исходных кода будут опубликованы в банке задач. Время и объяснение откроются после ответа.</p>
    <ErrorMessage error={submissions.error || contests.error || create.error} />
    {submissions.isPending || contests.isPending ? <p role="status">Загрузка посылок...</p> : candidates.length < 2 ? <p className="muted">Нужны две принятые посылки с измеренным временем. Посылки из текущих и будущих контестов публиковать нельзя.</p> : <form className="form" onSubmit={submit}>
      {[first, second].map((value, index) => <label className="field" key={index}><span>Код {index + 1}</span><select aria-label={`Код ${index + 1}: посылка`} value={value} disabled={create.isPending} required onChange={event => (index === 0 ? setFirst : setSecond)(event.target.value)}><option value="">Выберите посылку</option>{candidates.map(item => <option key={item.id} value={item.id}>#{item.id} - {item.timeMs} мс{item.contestTitle ? ` - ${item.contestTitle}` : ' - Практика'}</option>)}</select></label>)}
      <label className="field"><span>Объяснение</span><textarea value={explanation} maxLength={4000} required disabled={create.isPending} onChange={event => setExplanation(event.target.value)} /></label>
      {first === second && first && <p className="error">Выберите разные посылки.</p>}
      <button className="btn primary" disabled={create.isPending || !first || !second || first === second || !explanation.trim()}>{create.isPending ? 'Создаем...' : 'Опубликовать сравнение'}</button>
    </form>}
  </details>;
}
