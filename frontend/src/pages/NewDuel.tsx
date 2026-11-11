import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/client';
import type { DuelInput, DuelRequest, User } from '../api/types';
import { useSession } from '../auth';
import { ErrorMessage, LoginRequired, PageHeader, QueryState, UserLink } from '../ui';
import { difficultyLabels } from '../utils';

function duration(difficulty: DuelRequest['difficulty']) {
  return difficulty === 'easy' ? '1 час' : difficulty === 'medium' ? '2 часа' : difficulty === 'hard' ? '3 часа' : 'от 1 до 3 часов';
}

function OpponentInput({ value, onChange, disabled }: { value: string; onChange: (value: string) => void; disabled: boolean }) {
  const { user } = useSession();
  const id = useId();
  const [query, setQuery] = useState('');
  const [opened, setOpened] = useState(false);
  const [active, setActive] = useState(-1);
  useEffect(() => { const timer = setTimeout(() => setQuery(value.trim()), 250); return () => clearTimeout(timer); }, [value]);
  const users = useQuery({ queryKey: ['duel-opponent-search', query, user?.id], queryFn: ({ signal }) => api.listUsers({ q: query, sort: 'handle', limit: 8 }, signal), enabled: Boolean(query) && !disabled });
  const ready = query === value.trim();
  const options = ready ? users.data?.items.filter(person => person.id !== user?.id) ?? [] : [];
  function select(person: User) { onChange(person.handle); setOpened(false); setActive(-1); }
  function key(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') { event.preventDefault(); setOpened(false); setActive(-1); }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); setOpened(true);
      setActive(current => options.length ? current < 0 ? event.key === 'ArrowDown' ? 0 : options.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length : -1);
    }
    if (event.key === 'Enter' && opened && options.length) { event.preventDefault(); const person = options[active < 0 ? 0 : active]; if (person) select(person); }
  }
  return <div className="duel-opponent-picker" onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpened(false); }}>
    <label className="field"><span>Ник соперника</span><input role="combobox" aria-autocomplete="list" aria-expanded={opened && Boolean(value.trim())} aria-controls={id} aria-activedescendant={opened && active >= 0 && options[active] ? `${id}-${active}` : undefined} required autoComplete="off" value={value} disabled={disabled} onChange={event => { onChange(event.target.value); setOpened(true); setActive(-1); }} onFocus={() => setOpened(true)} onKeyDown={key} /></label>
    {opened && value.trim() && <div className="duel-opponent-results">
      {!ready || users.isFetching ? <p role="status">Поиск...</p> : users.error ? <ErrorMessage error={users.error} /> : options.length ? <ul id={id} role="listbox" aria-label="Участники">{options.map((person, index) => <li role="option" aria-selected={active === index} id={`${id}-${index}`} key={person.id} onMouseDown={event => event.preventDefault()} onClick={() => select(person)}>{person.handle}</li>)}</ul> : <p className="small muted">Участник не найден.</p>}
    </div>}
  </div>;
}

function DuelForm({ onCreated }: { onCreated: (request: DuelRequest) => void }) {
  const { user } = useSession();
  const [mode, setMode] = useState<DuelInput['mode']>('random');
  const [handle, setHandle] = useState('');
  const [problemId, setProblemId] = useState('');
  const pool = useQuery({ queryKey: ['duel-problem-pool', user?.id], queryFn: ({ signal }) => api.listDuelProblems(signal), enabled: mode === 'invite' });
  const problems = pool.data ?? [];
  const selected = mode === 'invite' ? problems.find(problem => problem.id === problemId) : undefined;
  const create = useMutation({ mutationFn: (body: DuelInput) => api.createDuel(body), onSuccess: onCreated });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (create.isPending || mode === 'invite' && !handle.trim()) return;
    create.mutate({ mode, ...(mode === 'invite' ? { opponentHandle: handle.trim(), ...(problemId ? { problemId } : {}) } : {}) });
  }
  return <form className="panel duel-search-form" onSubmit={submit}>
    <fieldset disabled={create.isPending} className="duel-mode"><legend>С кем сыграть</legend>
      <label className="duel-mode-option"><input type="radio" name="duel-mode" checked={mode === 'random'} onChange={() => setMode('random')} /><span>Случайный соперник</span></label>
      <label className="duel-mode-option"><input type="radio" name="duel-mode" checked={mode === 'invite'} onChange={() => setMode('invite')} /><span>Пригласить по нику</span></label>
    </fieldset>
    {mode === 'invite' ? <>
      <OpponentInput value={handle} onChange={setHandle} disabled={create.isPending} />
      <QueryState query={pool}><label className="field"><span>Задача</span><select aria-label="Задача дуэли" value={problemId} disabled={create.isPending} onChange={event => setProblemId(event.target.value)}><option value="">Случайная задача</option>{problems.map(problem => <option key={problem.id} value={problem.id}>{problem.title} - {difficultyLabels[problem.difficulty]}</option>)}</select></label></QueryState>
    </> : <p className="muted">Задача будет выбрана случайно после подбора соперника.</p>}
    <p className="small muted">Длительность: {duration(selected?.difficulty)}.</p>
    <ErrorMessage error={create.error} reveal />
    <button className="btn primary" disabled={create.isPending || mode === 'invite' && (!handle.trim() || pool.isPending || Boolean(pool.error))}>{create.isPending ? 'Отправляем...' : mode === 'random' ? 'Найти соперника' : 'Отправить приглашение'}</button>
  </form>;
}

export default function NewDuel() {
  const { user, isLoading } = useSession();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const cache = useQueryClient();
  const [confirmed, setConfirmed] = useState<DuelRequest | null>(null);
  const waiting = useRef(new Set<string>());
  const id = params.get('request');
  const query = useQuery({ queryKey: ['duel-request', id, user?.id], queryFn: ({ signal }) => api.getDuel(id ?? '', signal), enabled: Boolean(user && id), refetchInterval: value => value.state.data && ['queued', 'invited'].includes(value.state.data.status) ? 2000 : false });
  const request = id ? query.data ?? (confirmed?.id === id ? confirmed : undefined) : null;
  function keepRequest(value: DuelRequest) {
    setConfirmed(value);
    cache.setQueryData(['duel-request', value.id, user?.id], value);
    void cache.invalidateQueries({ queryKey: ['duels'] });
    void cache.invalidateQueries({ queryKey: ['notifications'] });
    if (value.status === 'matched' && value.contestId && Date.parse(value.expiresAt) > Date.now()) navigate(`/contest/${encodeURIComponent(value.contestId)}`, { replace: true });
    else { if (['queued', 'invited'].includes(value.status)) waiting.current.add(value.id); setParams({ request: value.id }, { replace: true }); }
  }
  const action = useMutation({ mutationFn: (kind: 'cancel' | 'accept' | 'decline') => kind === 'accept' ? api.acceptDuel(request!.id) : kind === 'decline' ? api.declineDuel(request!.id) : api.cancelDuel(request!.id), onSuccess: keepRequest });
  useEffect(() => {
    if (!request) return;
    if (['queued', 'invited'].includes(request.status)) waiting.current.add(request.id);
    if (request.status === 'matched' && request.contestId && waiting.current.has(request.id) && Date.parse(request.expiresAt) > Date.now()) {
      void cache.invalidateQueries({ queryKey: ['duels'] });
      navigate(`/contest/${encodeURIComponent(request.contestId)}`, { replace: true });
    }
  }, [request, navigate, cache]);
  if (isLoading) return <p role="status">Загрузка аккаунта...</p>;
  if (!user) return <><PageHeader title="Новая дуэль" /><LoginRequired returnTo={location.pathname + location.search}>Войдите, чтобы найти соперника или пригласить друга.</LoginRequired></>;
  const pending = request && ['queued', 'invited'].includes(request.status);
  const recipient = request?.opponent?.id === user.id;
  const ended = request?.status === 'matched' && Date.parse(request.expiresAt) <= Date.now();
  return <div className="duels-page">
    <PageHeader title="Новая дуэль" description="Два участника и одна общая задача." actions={<Link className="text-link" to="/duels">Мои дуэли</Link>} />
    {id && query.isPending && !request ? <p role="status">Загрузка дуэли...</p> : id && query.error && !request ? <><ErrorMessage error={query.error} /><button className="btn secondary" onClick={() => void query.refetch()}>Повторить</button></> : request ? <section className="panel duel-waiting" aria-label="Состояние дуэли">
      <h2 aria-live="polite">{ended ? 'Дуэль завершена' : ({ queued: 'Ищем соперника', invited: 'Ожидаем ответ на приглашение', matched: 'Соперник найден', declined: 'Приглашение отклонено', cancelled: 'Поиск или приглашение отменены', expired: 'Время ожидания истекло' })[request.status]}</h2>
      {request.mode === 'invite' && <p>{recipient ? <><UserLink user={request.creator} /> приглашает вас на дуэль.</> : request.opponent && <>Соперник: <UserLink user={request.opponent} />.</>}</p>}
      {request.problemTitle && <p>Задача: {request.problemTitle}.</p>}
      <p className="small muted">Длительность: {duration(request.difficulty)}.</p>
      <ErrorMessage error={query.error || action.error} reveal />
      <div className="duel-request-actions">{request.status === 'invited' && recipient ? <><button className="btn primary" disabled={action.isPending} onClick={() => action.mutate('accept')}>Принять дуэль</button><button className="btn secondary" disabled={action.isPending} onClick={() => action.mutate('decline')}>Отклонить</button></> : pending && request.creator.id === user.id ? <button className="btn secondary" disabled={action.isPending} onClick={() => action.mutate('cancel')}>{action.isPending ? 'Отменяем...' : request.status === 'queued' ? 'Отменить поиск' : 'Отменить приглашение'}</button> : null}
        <Link className="btn secondary" to="/duels/new" onClick={() => action.reset()}>Еще одна дуэль</Link>
        {request.contestId && <Link className="btn primary" to={`/contest/${encodeURIComponent(request.contestId)}`}>Открыть дуэль</Link>}
      </div>
    </section> : <DuelForm key={user.id} onCreated={keepRequest} />}
  </div>;
}
