import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import { ErrorMessage, QueryState } from '../ui';

const labels = { queued: 'Проверяется', successful: 'Успешный взлом', unsuccessful: 'Взлом не удался', invalid: 'Недопустимый тест', system_error: 'Сбой проверки' };

export function HackForm({ submissionId, contestId }: { submissionId: string; contestId: string }) {
  const [testJson, setTestJson] = useState('{"scalars": {}, "inputs": {}}');
  const client = useQueryClient();
  const create = useMutation({ mutationFn: () => api.createHack(submissionId, testJson), onSuccess: () => { void client.invalidateQueries({ queryKey: ['hacks', contestId] }); } });
  return <section><h2>Взлом решения</h2><p>Введите JSON с полями scalars и inputs согласно сигнатуре задачи. Ответ вычислит эталон. До 8192 входных элементов и 64 КБ JSON. Взломы по времени не учитываются.</p>
    <form className="form" onSubmit={event => { event.preventDefault(); create.mutate(); }}>
      <label className="field">Входной тест<textarea rows={8} maxLength={65536} value={testJson} onChange={event => { setTestJson(event.target.value); create.reset(); }} /></label>
      <button className="btn secondary" disabled={create.isPending}>{create.isPending ? 'Отправляем...' : 'Проверить взлом'}</button>
      <ErrorMessage error={create.error} />{create.data && <p role="status">Тест принят, результат появится в списке ниже.</p>}
    </form><HackList contestId={contestId} /></section>;
}

export function HackList({ contestId }: { contestId: string }) {
  const { user } = useSession();
  const client = useQueryClient();
  const list = useQuery({ queryKey: ['hacks', contestId], queryFn: ({ signal }) => api.listHacks(contestId, signal), refetchInterval: 5000 });
  const retry = useMutation({ mutationFn: (id: string) => api.retryHack(id), onSuccess: () => client.invalidateQueries({ queryKey: ['hacks', contestId] }) });
  return <QueryState query={list}><ErrorMessage error={retry.error} />{list.data?.length ? <ul>{list.data.map(hack => <li key={hack.id}><Link to={`/submission/${hack.submissionId}`}>{hack.problemId}</Link>: {labels[hack.status]}{hack.diagnostic && ` · ${hack.diagnostic}`} {hack.status === 'system_error' && hack.userId === user?.id && <button className="text-link" disabled={retry.isPending} onClick={() => retry.mutate(hack.id)}>Повторить</button>}</li>)}</ul> : <p>Взломов пока нет.</p>}</QueryState>;
}
