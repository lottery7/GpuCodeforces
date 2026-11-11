import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { DuelInput, DuelRequest } from '../api/types';
import { useSession } from '../auth';
import { GPU_CUDA, useProcessors } from '../processors';
import { ErrorMessage, LoginRequired, PageHeader, QueryState, UserLink } from '../ui';

export const duelStatusLabels: Record<DuelRequest['status'], string> = {
  queued: 'Ищем соперника', invited: 'Ожидаем ответ на приглашение', matched: 'Соперник найден',
  declined: 'Приглашение отклонено', cancelled: 'Поиск или приглашение отменены', expired: 'Время ожидания истекло',
};

function DuelForm({ onCreated }: { onCreated: (request: DuelRequest) => void }) {
  const [mode, setMode] = useState<DuelInput['mode']>('random');
  const [handle, setHandle] = useState('');
  const [topics, setTopics] = useState<string[]>([]);
  const catalog = useProcessors();
  // the duel runs on one processor: its problem is solvable there, and topics come from such problems
  const [processor, setProcessor] = useState(GPU_CUDA);
  const choices = useQuery({ queryKey: ['duel-topics', processor], queryFn: ({ signal }) => api.listDuelTopics(processor, signal) });
  const create = useMutation({ mutationFn: (body: DuelInput) => api.createDuel(body), onSuccess: onCreated });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (create.isPending || mode === 'invite' && !handle.trim()) return;
    create.mutate({ mode, topics, processor, ...(mode === 'invite' ? { opponentHandle: handle.trim() } : {}) });
  }
  return <form className="panel duel-search-form" onSubmit={submit}>
    <fieldset disabled={create.isPending} className="duel-mode"><legend>С кем сыграть</legend>
      <label className="duel-mode-option"><input type="radio" name="duel-mode" checked={mode === 'random'} onChange={() => setMode('random')} /><span>Случайный соперник</span></label>
      <label className="duel-mode-option"><input type="radio" name="duel-mode" checked={mode === 'invite'} onChange={() => setMode('invite')} /><span>Пригласить по нику</span></label>
    </fieldset>
    {catalog.processors.length > 1 && <label className="field"><span>Процессор</span><select name="processor" aria-label="Процессор дуэли" value={processor} disabled={create.isPending} onChange={event => { setProcessor(event.target.value); setTopics([]); }}>
      {catalog.processors.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
    </select></label>}
    {mode === 'invite' && <label className="field"><span>Ник соперника</span><input name="opponentHandle" required autoComplete="off" maxLength={100} value={handle} disabled={create.isPending} onChange={event => setHandle(event.target.value)} /></label>}
    <fieldset className="duel-topics" disabled={create.isPending}><legend>Темы задач</legend>
      <p className="small muted">Выберите интересующие темы или оставьте все. Общую задачу случайно выберет сервер после подбора соперника.</p>
      <QueryState query={choices}><div className="duel-topic-options">{choices.data?.map(topic => <label className={topics.includes(topic) ? 'duel-topic selected' : 'duel-topic'} key={topic}><input type="checkbox" checked={topics.includes(topic)} onChange={event => setTopics(current => event.target.checked ? [...current, topic] : current.filter(item => item !== topic))} />{topic}</label>)}</div></QueryState>
    </fieldset>
    <ErrorMessage error={create.error} reveal />
    <button className="btn primary" disabled={create.isPending || choices.isPending || Boolean(choices.error) || mode === 'invite' && !handle.trim()}>{create.isPending ? 'Отправляем...' : mode === 'random' ? 'Найти соперника' : 'Отправить приглашение'}</button>
  </form>;
}

export default function Duels() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const cache = useQueryClient();
  const [confirmedRequest, setConfirmedRequest] = useState<DuelRequest | null>(null);
  const id = params.get('request');
  const fresh = params.get('new') === '1';
  const current = useQuery({
    queryKey: ['current-duel', user?.id], queryFn: ({ signal }) => api.getCurrentDuel(signal),
    enabled: Boolean(user && !id && !fresh), refetchInterval: query => query.state.data && ['queued', 'invited'].includes(query.state.data.status) ? 2000 : false,
  });
  const requested = useQuery({
    queryKey: ['duel-request', id, user?.id], queryFn: ({ signal }) => api.getDuel(id ?? '', signal),
    enabled: Boolean(user && id), refetchInterval: query => query.state.data && ['queued', 'invited'].includes(query.state.data.status) ? 2000 : false,
  });
  const request = id ? requested.data ?? (confirmedRequest?.id === id ? confirmedRequest : undefined) : fresh ? null : current.data;
  function keepRequest(value: DuelRequest) {
    setConfirmedRequest(value);
    cache.setQueryData(['duel-request', value.id, user?.id], value);
    cache.setQueryData(['current-duel', user?.id], value);
    void cache.invalidateQueries({ queryKey: ['notifications'] });
    setParams({ request: value.id }, { replace: true });
  }
  const action = useMutation({
    mutationFn: (kind: 'cancel' | 'accept' | 'decline') => kind === 'accept' ? api.acceptDuel(request!.id) : kind === 'decline' ? api.declineDuel(request!.id) : api.cancelDuel(request!.id),
    onSuccess: keepRequest,
  });
  useEffect(() => {
    if (!id && !fresh && current.isFetchedAfterMount && !current.isFetching && !current.error && current.data && ['queued', 'invited'].includes(current.data.status)) {
      setConfirmedRequest(current.data);
      cache.setQueryData(['duel-request', current.data.id, user?.id], current.data);
      setParams({ request: current.data.id }, { replace: true });
    }
  }, [id, fresh, current.data, current.isFetchedAfterMount, current.isFetching, current.error, cache, user?.id, setParams]);
  useEffect(() => {
    const confirmed = id ? confirmedRequest?.id === request?.id || requested.isFetchedAfterMount && !requested.isFetching && !requested.error : current.isFetchedAfterMount && !current.isFetching && !current.error;
    if (confirmed && request?.status === 'matched' && request.contestId && Date.parse(request.expiresAt) > Date.now()) {
      void cache.invalidateQueries({ queryKey: ['contests'] });
      navigate(`/contest/${encodeURIComponent(request.contestId)}`, { replace: true });
    }
  }, [request, id, confirmedRequest, current.isFetchedAfterMount, current.isFetching, current.error, requested.isFetchedAfterMount, requested.isFetching, requested.error, navigate, cache]);
  if (isLoading) return <p role="status">Загрузка аккаунта...</p>;
  if (!user) return <><PageHeader title="Новая дуэль" /><LoginRequired returnTo={location.pathname + location.search}>Войдите, чтобы найти соперника или пригласить друга.</LoginRequired></>;
  const query = id ? requested : current;
  const restoring = !(fresh && !id);
  const waiting = request && ['queued', 'invited'].includes(request.status);
  const recipient = request?.opponent?.id === user.id;
  const finished = request?.status === 'matched' && Date.parse(request.expiresAt) <= Date.now();
  return <div className="duels-page">
    <PageHeader title="Дуэль" description="Два участника, одна случайная задача и 30 минут на решение." actions={<Link className="text-link" to="/contests">К соревнованиям</Link>} />
    {restoring && (query.isPending && !request || !id && !current.isFetchedAfterMount && current.isFetching) ? <p role="status">Загрузка дуэли...</p> : restoring && query.error && !request ? <><ErrorMessage error={query.error} /><button className="btn secondary" onClick={() => void query.refetch()}>Повторить</button></> : request ? <section className="panel duel-waiting" aria-label="Состояние дуэли">
      <div className={waiting ? 'duel-wait-indicator' : 'duel-finished-indicator'} aria-hidden="true" />
      <h2 aria-live="polite">{finished ? 'Дуэль завершена' : duelStatusLabels[request.status]}</h2>
      {request.mode === 'invite' && <p>{recipient ? <><UserLink user={request.creator} /> приглашает вас на дуэль.</> : request.opponent && <>Соперник: <UserLink user={request.opponent} />.</>}</p>}
      {waiting && <p className="muted">{request.status === 'queued' ? 'Оставьте страницу открытой. Когда найдется соперник, дуэль откроется автоматически.' : recipient ? 'Примите приглашение, чтобы начать дуэль. Задача будет выбрана случайно.' : 'Соперник получит уведомление в приложении. После принятия дуэль откроется автоматически.'}</p>}
      <p className="small muted">Темы: {request.topics.length ? request.topics.join(', ') : 'Любые'}</p>
      <ErrorMessage error={query.error || action.error} reveal />
      <div className="duel-request-actions">{request.status === 'invited' && recipient ? <><button className="btn primary" disabled={action.isPending} onClick={() => action.mutate('accept')}>Принять дуэль</button><button className="btn secondary" disabled={action.isPending} onClick={() => action.mutate('decline')}>Отклонить</button></> : waiting && request.creator.id === user.id ? <button className="btn secondary" disabled={action.isPending} onClick={() => action.mutate('cancel')}>{action.isPending ? 'Отменяем...' : request.status === 'queued' ? 'Отменить поиск' : 'Отменить приглашение'}</button> : null}
        {!waiting && <button className="btn primary" onClick={() => { action.reset(); setParams({ new: '1' }); }}>Новая дуэль</button>}
        {request.contestId && <Link className="btn primary" to={`/contest/${encodeURIComponent(request.contestId)}`}>Открыть дуэль</Link>}
      </div>
    </section> : <DuelForm key={user.id} onCreated={keepRequest} />}
  </div>;
}
