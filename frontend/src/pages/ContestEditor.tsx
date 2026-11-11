import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api } from '../api/client';
import type { ContestInput, User } from '../api/types';
import { useSession } from '../auth';
import { GPU_CUDA, useProcessors } from '../processors';
import { Difficulty, Empty, ErrorMessage, LoginRequired, PageHeader, UserLink, useFeedbackVisibility } from '../ui';
import { difficultyLabels, phaseOf } from '../utils';

type Values = { title: string; startAt: string; duration: string; hackDuration: string; processor: string; authors: User[]; problemIds: string[] };

function moscowInput(date: string) {
  return new Date(Date.parse(date) + 3 * 3600000).toISOString().slice(0, 16);
}

export default function ContestEditor() {
  const { id } = useParams();
  const { user, isLoading } = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const catalog = useProcessors();
  const [values, setValues] = useState<Values | null>(null);
  const initialized = useRef('');
  const [authorQuery, setAuthorQuery] = useState('');
  const [authorSearch, setAuthorSearch] = useState('');
  const [query, setQuery] = useState('');
  const [difficulty, setDifficulty] = useState('all');
  const [tag, setTag] = useState('all');
  const [selectedOnly, setSelectedOnly] = useState(false);
  const [formError, setFormError] = useState<Error | null>(null);
  const formErrorRef = useFeedbackVisibility<HTMLDivElement>(formError);
  const formKey = `${id ?? 'new'}:${user?.id ?? ''}`;
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => tick(value => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    const timer = window.setTimeout(() => setAuthorSearch(authorQuery.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [authorQuery]);
  const contest = useQuery({
    queryKey: ['contest', id, user?.id],
    queryFn: ({ signal }) => api.getContest(id ?? '', signal),
    enabled: Boolean(id && user),
  });
  const pool = useQuery({
    queryKey: ['contest-problem-pool', user?.id],
    queryFn: ({ signal }) => api.allProblems({}, signal),
    enabled: Boolean(user),
  });
  const snapshots = useQuery({
    queryKey: ['contest-edit-tasks', id, user?.id],
    queryFn: ({ signal }) => api.allProblems({ context: id }, signal),
    enabled: Boolean(id && user && contest.data?.authors.some(author => author.id === user.id)),
  });
  const users = useQuery({
    queryKey: ['author-search', authorSearch],
    queryFn: ({ signal }) => api.listUsers({ q: authorSearch, sort: 'handle', limit: 8 }, signal),
    enabled: Boolean(user && authorSearch),
    staleTime: 30000,
  });
  useEffect(() => {
    // a new contest starts on a processor the platform offers, so its form waits for the catalogue
    if (!user || initialized.current === formKey || (id ? !contest.data : catalog.isPending)) return;
    const item = id ? contest.data : undefined;
    initialized.current = formKey;
    setValues({
      title: item?.title ?? '',
      startAt: moscowInput(item?.startAt ?? new Date(Date.now() + 3600000).toISOString()),
      duration: String(item ? Math.round((Date.parse(item.endAt) - Date.parse(item.startAt)) / 60000) : 120),
      hackDuration: String(item?.hackEndAt ? Math.round((Date.parse(item.hackEndAt) - Date.parse(item.endAt)) / 60000) : 0),
      processor: item?.processor ?? catalog.find(GPU_CUDA)?.id ?? catalog.processors[0]?.id ?? GPU_CUDA,
      authors: item?.authors ?? [user],
      problemIds: [...(item?.problemIds ?? [])],
    });
    setAuthorQuery('');
    setAuthorSearch('');
    setQuery('');
    setDifficulty('all');
    setTag('all');
    setSelectedOnly(false);
    setFormError(null);
  }, [contest.data, id, user, formKey, catalog.isPending]);
  const save = useMutation({
    mutationFn: (body: ContestInput) => id ? api.updateContest(id, body) : api.createContest(body),
    onSuccess: saved => {
      queryClient.setQueryData(['contest', saved.id, user?.id], saved);
      void queryClient.invalidateQueries({ queryKey: ['contests'] });
      void queryClient.invalidateQueries({ queryKey: ['contest', saved.id] });
      void queryClient.invalidateQueries({ queryKey: ['contest-tasks', saved.id] });
      void queryClient.invalidateQueries({ queryKey: ['contest-edit-tasks', saved.id] });
      navigate(`/contest/${encodeURIComponent(saved.id)}`);
    },
  });
  const resetSave = save.reset;
  useEffect(() => resetSave(), [id, user?.id, resetSave]);

  if (isLoading) return <p className="loading" role="status">Загрузка аккаунта...</p>;
  if (!user) return <><PageHeader title={id ? 'Настройки контеста' : 'Новый контест'} /><LoginRequired returnTo={location.pathname + location.search}>Войдите, чтобы создать или изменить контест.</LoginRequired></>;
  if (id && contest.isPending) return <p className="loading" role="status">Загрузка контеста...</p>;
  if (contest.error && id && contest.data === undefined) return <><PageHeader title="Настройки контеста" /><ErrorMessage error={contest.error} /><button className="text-link" onClick={() => void contest.refetch()}>Повторить</button></>;
  if (contest.data?.format === 'duel') return <><PageHeader title="Дуэль" /><p>Участники и общая задача назначаются при подборе.</p><Link className="btn secondary" to={`/contest/${encodeURIComponent(id ?? '')}`}>Открыть дуэль</Link></>;
  if (id && contest.data && (phaseOf(contest.data) !== 'upcoming' || !contest.data.authors.some(author => author.id === user.id))) return <><PageHeader title="Настройки недоступны" /><Empty>Изменять контест могут его авторы до начала соревнования.</Empty><Link className="btn secondary" to={`/contest/${encodeURIComponent(id)}`}>К контесту</Link></>;
  if (pool.error && pool.data === undefined || id && snapshots.error && snapshots.data === undefined) return <><PageHeader title={id ? 'Настройки контеста' : 'Новый контест'} /><ErrorMessage error={pool.error && pool.data === undefined ? pool.error : snapshots.error} /><button className="text-link" onClick={() => { void pool.refetch(); if (id) void snapshots.refetch(); }}>Повторить</button></>;
  if (!values || initialized.current !== formKey || pool.isPending || id && snapshots.isPending) return <p className="loading" role="status">Загрузка задач...</p>;

  // A contest's own versions show only its current processor, so whether a problem supports another one is read
  // from the bank where it is there.
  const bankProcessors = new Map((pool.data ?? []).map(problem => [problem.id, problem.processors.map(item => item.id)]));
  const supports = (problem: { id: string; processors: { id: string }[] }) =>
    (bankProcessors.get(problem.id) ?? problem.processors.map(item => item.id)).includes(values.processor);
  const problems = [...new Map([...(pool.data ?? []), ...(snapshots.data ?? [])].map(problem => [problem.id, problem])).values()].filter(supports);
  const problemMap = new Map(problems.map(problem => [problem.id, problem]));
  const selectedIds = new Set(values.problemIds);
  const hackable = values.problemIds.flatMap(problemId => problemMap.get(problemId)?.hacksAllowed ? [problemMap.get(problemId)!.title] : []);
  const hackPeriod = Number(values.hackDuration) > 0 && values.problemIds.length > 0;
  const tags = [...new Set(problems.flatMap(problem => problem.tags))].sort((a, b) => a.localeCompare(b));
  const ordered = selectedOnly ? values.problemIds.map(problemId => problemMap.get(problemId)).filter(problem => problem !== undefined) : problems;
  const visible = ordered.filter(problem => (`${problem.code} ${problem.title}`).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()) && (difficulty === 'all' || problem.difficulty === difficulty) && (tag === 'all' || problem.tags.includes(tag)));
  const missing = values.problemIds.filter(problemId => !problemMap.has(problemId));
  const changeProcessor = (processor: string) => {
    const allowed = new Set([...(pool.data ?? []), ...(snapshots.data ?? [])].filter(problem => (bankProcessors.get(problem.id) ?? problem.processors.map(item => item.id)).includes(processor)).map(problem => problem.id));
    // problems the new processor cannot judge leave the contest
    update({ processor, problemIds: values.problemIds.filter(problemId => allowed.has(problemId)) });
  };
  const authorSearchReady = authorSearch === authorQuery.trim();
  const matches = authorSearchReady ? users.data?.items.filter(person => !values.authors.some(author => author.id === person.id)) ?? [] : [];
  const filtersActive = Boolean(query || difficulty !== 'all' || tag !== 'all' || selectedOnly);
  const backgroundError = contest.error ?? pool.error ?? (id ? snapshots.error : null);
  const update = (patch: Partial<Values>) => setValues(current => current ? { ...current, ...patch } : current);
  const addAuthor = (person: User) => {
    update({ authors: [...values.authors, person] });
    setAuthorQuery('');
    setAuthorSearch('');
  };
  const move = (problemId: string, direction: number) => {
    const next = [...values.problemIds];
    const from = next.indexOf(problemId);
    const to = from + direction;
    if (from < 0 || to < 0 || to >= next.length) return;
    const target = next[to];
    if (target === undefined) return;
    next[to] = problemId;
    next[from] = target;
    update({ problemIds: next });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (save.isPending) return;
    setFormError(null);
    save.reset();
    const start = Date.parse(`${values.startAt}+03:00`);
    const minutes = Number(values.duration);
    if (!values.title.trim()) { setFormError(new Error('Введите название контеста.')); return; }
    if (!Number.isFinite(start) || start <= Date.now()) { setFormError(new Error('Начало контеста должно быть в будущем.')); return; }
    const end = new Date(start + minutes * 60000);
    if (!Number.isInteger(minutes) || minutes < 1 || !Number.isFinite(end.getTime())) { setFormError(new Error('Укажите длительность в минутах.')); return; }
    if (!values.authors.length) { setFormError(new Error('Нужен хотя бы один автор.')); return; }
    if (!values.problemIds.length) { setFormError(new Error('Выберите хотя бы одну задачу.')); return; }
    save.mutate({
      title: values.title.trim(),
      startAt: new Date(start).toISOString(),
      endAt: end.toISOString(),
      authorIds: values.authors.map(author => author.id),
      problemIds: values.problemIds,
      hackDurationMinutes: Number(values.hackDuration),
      processor: values.processor,
      format: 'standard',
    });
  };

  return <>
    <PageHeader title={id ? 'Настройки контеста' : 'Новый контест'} actions={<Link className="btn secondary" to={id ? `/contest/${encodeURIComponent(id)}` : '/contests?tabs=mine'}>{id ? 'К контесту' : 'Мои контесты'}</Link>} />
    <form className="form contest-form contest-builder" onSubmit={submit} aria-busy={save.isPending}>
      <div className="contest-settings">
        <label className="field"><span>Название</span><input name="title" required maxLength={100} value={values.title} disabled={save.isPending} onChange={event => update({ title: event.target.value })} /></label>
        <div className="field-pair">
          <label className="field"><span>Начало (МСК)</span><input name="startAt" type="datetime-local" required value={values.startAt} disabled={save.isPending} onChange={event => update({ startAt: event.target.value })} /></label>
          <label className="field"><span>Длительность, минут</span><input name="duration" type="number" min={1} step={1} required value={values.duration} disabled={save.isPending} onChange={event => update({ duration: event.target.value })} /></label>
        </div>
        <label className="field"><span>Процессор</span><select name="processor" aria-label="Процессор" value={values.processor} disabled={save.isPending} onChange={event => changeProcessor(event.target.value)}>
          {(catalog.processors.some(item => item.id === values.processor) ? catalog.processors : [...catalog.processors, { id: values.processor, title: values.processor, model: '' }]).map(item => <option key={item.id} value={item.id}>{item.title}{item.model && ` (${item.model})`}</option>)}
        </select><span className="small muted">Все посылки контеста проверяются на нём; в списке задач остаются только задачи, которые можно сдавать на этом процессоре.</span></label>
        <label className="field"><span>Период взломов, минут (0 — отключён)</span><input name="hackDuration" type="number" min={0} max={10080} step={1} required value={values.hackDuration} disabled={save.isPending} aria-describedby="hack-hint" onChange={event => update({ hackDuration: event.target.value })} /><span id="hack-hint" className="small muted">{!hackPeriod ? 'После окончания контеста участники ищут тесты, на которых чужие принятые решения ошибаются. Задачи, которые это допускают, помечены в списке.' : hackable.length ? `Взламывать можно решения задач: ${hackable.join(', ')}.` : <span className="hack-warning">Ни одна из выбранных задач не допускает взломы: в этом периоде взламывать будет нечего.</span>}</span></label>
        <fieldset className="contest-authors" disabled={save.isPending}>
          <legend>Авторы</legend>
          <p className="small muted">{values.authors.some(author => author.id === user.id) ? 'Авторы могут изменять контест до начала.' : 'После сохранения вы потеряете право редактирования.'}</p>
          <div className="contest-selected-authors">{values.authors.map(author => <div className="contest-selected-author" key={author.id}><UserLink user={author} /><button type="button" className="text-link" aria-label={`Убрать ${author.handle}`} disabled={values.authors.length === 1 || !id && author.id === user.id} title={values.authors.length === 1 ? 'Нужен хотя бы один автор' : !id && author.id === user.id ? 'Новый контест создаёт один из его авторов; выйти из авторов можно после создания' : undefined} onClick={() => update({ authors: values.authors.filter(person => person.id !== author.id) })}>Убрать</button></div>)}</div>
          <input type="search" aria-label="Найти автора по нику" placeholder="Ник пользователя" value={authorQuery} onChange={event => setAuthorQuery(event.target.value)} onKeyDown={event => {
            if (event.key === 'Escape') { setAuthorQuery(''); setAuthorSearch(''); }
            if (event.key !== 'Enter') return;
            event.preventDefault();
            const exact = matches.find(person => person.handle.toLocaleLowerCase() === authorQuery.trim().toLocaleLowerCase());
            const candidate = exact ?? (matches.length === 1 ? matches[0] : undefined);
            if (candidate) addAuthor(candidate);
          }} />
          {authorQuery.trim() && <div className="contest-author-results" aria-label="Результаты поиска авторов">
            {!authorSearchReady || users.isFetching ? <p className="small muted" role="status">Поиск...</p> : users.error ? <><ErrorMessage error={users.error} /><button className="text-link" type="button" onClick={() => void users.refetch()}>Повторить</button></> : matches.length ? matches.map(person => <div className="contest-author-result" key={person.id}><UserLink user={person} /><button type="button" className="text-link" aria-label={`Добавить ${person.handle}`} onClick={() => addAuthor(person)}>Добавить</button></div>) : authorSearch && <p className="small muted">{users.data?.items.length ? 'Все найденные пользователи уже добавлены.' : 'Пользователь не найден.'}</p>}
          </div>}
        </fieldset>
      </div>
      <div className="contest-task-picker">
        <fieldset className="problem-choices" disabled={save.isPending}>
          <legend>Задачи</legend>
          <div className="toolbar contest-picker-summary"><span className="small muted" aria-live="polite">Выбрано: {values.problemIds.length}</span><label className="checkbox-label"><input type="checkbox" checked={selectedOnly} onChange={event => setSelectedOnly(event.target.checked)} />Только выбранные</label></div>
          <div className="toolbar contest-picker-filters">
            <div className="search"><input type="search" aria-label="Найти задачу для контеста" placeholder="Название или номер задачи" value={query} onChange={event => setQuery(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault(); }} /></div>
            <select aria-label="Сложность задач" value={difficulty} onChange={event => setDifficulty(event.target.value)}><option value="all">Сложность</option>{Object.entries(difficultyLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
            <select aria-label="Тема задач" value={tag} onChange={event => setTag(event.target.value)}><option value="all">Тема</option>{tags.map(value => <option key={value}>{value}</option>)}</select>
            {filtersActive && <button type="button" className="text-link filter-reset" onClick={() => { setQuery(''); setDifficulty('all'); setTag('all'); setSelectedOnly(false); }}>Сбросить</button>}
          </div>
          <div className="contest-problem-list">
            {visible.map(problem => <div className="problem-choice-row" key={problem.id}>
              <label className="problem-choice"><input type="checkbox" checked={selectedIds.has(problem.id)} onChange={event => update({ problemIds: event.target.checked ? [...values.problemIds, problem.id] : values.problemIds.filter(problemId => problemId !== problem.id) })} /><span className="problem-choice-title">{problem.code}. {problem.title}</span></label>
              {problem.hacksAllowed && <span className="small muted">взломы</span>}
              <Difficulty difficulty={problem.difficulty} />
              {selectedOnly && values.problemIds.length > 1 && <div className="choice-actions"><button type="button" className="text-link" aria-label={`Поднять ${problem.title}`} disabled={values.problemIds[0] === problem.id} onClick={() => move(problem.id, -1)}>Выше</button><button type="button" className="text-link" aria-label={`Опустить ${problem.title}`} disabled={values.problemIds.at(-1) === problem.id} onClick={() => move(problem.id, 1)}>Ниже</button></div>}
            </div>)}
            {!visible.length && <p className="muted">Нет задач по этим фильтрам.</p>}
            {missing.map(problemId => <div className="problem-choice-row" key={problemId}><span>{problemId} (задача недоступна)</span><button type="button" className="text-link" onClick={() => update({ problemIds: values.problemIds.filter(value => value !== problemId) })}>Убрать</button></div>)}
          </div>
        </fieldset>
        <div className="contest-builder-actions">
          <div className="form-feedback">
            {formError && <div className="notice error feedback-anchor" role="alert" ref={formErrorRef}>{formError.message}</div>}
            <ErrorMessage error={save.error} reveal />
            {backgroundError && <><ErrorMessage error={backgroundError} /><button type="button" className="text-link" onClick={() => {
              if (contest.error) void contest.refetch();
              if (pool.error) void pool.refetch();
              if (id && snapshots.error) void snapshots.refetch();
            }}>Повторить загрузку</button></>}
          </div>
          <button className="btn primary" type="submit" disabled={save.isPending || !values.problemIds.length}>{save.isPending ? 'Сохранение...' : id ? 'Сохранить' : 'Создать контест'}</button>
        </div>
      </div>
    </form>
  </>;
}
