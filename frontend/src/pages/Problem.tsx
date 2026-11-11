import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, ApiError } from '../api/client';
import type { Contest, GuessInput, ProblemDetail, Submission } from '../api/types';
import { useSession } from '../auth';
import CodeEditor from '../components/CodeEditor';
import GuessResult from '../components/GuessResult';
import DuelBoard from '../components/DuelBoard';
import BenchmarkInfo from '../components/BenchmarkInfo';
import ReferenceStatus from '../components/ReferenceStatus';
import { readDraft, rememberEditorLogin, writeDraft } from '../drafts';
import { Empty, ErrorMessage, Markdown, PageHeader, QueryState, Tabs, TimeValue, UserLink, useFeedbackVisibility } from '../ui';
import { canJoinContest, contextFromSearch, difficultyLabels, formatLabels, guessLabels, problemPath } from '../utils';
import { SubmissionList } from './Submissions';

function phaseOf(contest: Contest | undefined, now: number) {
  if (!contest) return null;
  return now < Date.parse(contest.startAt) ? 'upcoming' : now >= Date.parse(contest.endAt) ? 'finished' : 'live';
}

function EditorPane({ problem, context, contest, contestLoading, phase, onCloseOutput }: { problem: ProblemDetail; context: string; contest?: Contest; contestLoading: boolean; phase: ReturnType<typeof phaseOf>; onCloseOutput: () => void }) {
  const { user } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [code, setCode] = useState(() => readDraft(user?.id || null, problem.id, context, problem.starterCode));
  const [view, setView] = useState<'solution' | 'original'>('solution');
  const [saved, setSaved] = useState(true);
  const [undo, setUndo] = useState<{ code: string; message: string } | null>(null);
  const [localError, setLocalError] = useState<Error | null>(null);
  const [example, setExample] = useState<{ code: string; passed: boolean; diagnostic: string } | null>(null);
  function clearErrors() {
    setLocalError(null);
    if (check.isError) check.reset();
    if (submit.isError) submit.reset();
    if (join.isError) join.reset();
  }
  function changeCode(value: string) {
    setCode(value);
    setSaved(writeDraft(user?.id || null, problem.id, context, value));
    clearErrors();
    setUndo(null);
  }
  function requestLogin() {
    rememberEditorLogin(problem.id, context, code, location.pathname + location.search);
    navigate(`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`);
  }
  const check = useMutation({
    mutationFn: (snapshot: string) => api.checkExample(problem.id, context, snapshot),
    onSuccess: (result, snapshot) => setExample({ code: snapshot, ...result }),
    onError: error => { if (error instanceof ApiError && error.status === 401) requestLogin(); },
  });
  const submit = useMutation({
    mutationFn: () => api.submitSolution({ problemId: problem.id, ...(context !== 'practice' ? { contestId: context } : {}), code }),
    onSuccess: item => {
      void queryClient.invalidateQueries();
      navigate(`/submission/${encodeURIComponent(item.id)}`);
    },
    onError: error => { if (error instanceof ApiError && error.status === 401) requestLogin(); },
  });
  const join = useMutation({
    mutationFn: () => api.registerForContest(context),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['contest', context] }),
    onError: error => { if (error instanceof ApiError && error.status === 401) requestLogin(); },
  });
  const busy = check.isPending || submit.isPending || join.isPending;
  const error = localError || submit.error || join.error || check.error;
  const feedback = useMemo(() => ({ error, example, undo, pending: check.isPending }), [error, example, undo, check.isPending]);
  const feedbackRef = useFeedbackVisibility(feedback, Boolean(error || example || undo || check.isPending));
  function closeOutput() {
    clearErrors();
    setExample(null);
    setUndo(null);
    check.reset();
    onCloseOutput();
  }
  function reset() {
    changeCode(problem.starterCode);
    setUndo({ code, message: problem.format === 'optimization' ? 'Исходный код восстановлен.' : 'Шаблон восстановлен.' });
    setExample(null);
    check.reset();
  }
  function practice() {
    setSaved(writeDraft(user?.id || null, problem.id, 'practice', code));
    navigate(problemPath(problem.id));
  }
  function download() {
    const url = URL.createObjectURL(new Blob([view === 'original' ? problem.starterCode : code], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'solution.cu';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function switchCodeTab(event: KeyboardEvent<HTMLDivElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 'solution' : event.key === 'End' ? 'original' : view === 'solution' ? 'original' : 'solution';
    setView(next);
    event.currentTarget.querySelector<HTMLButtonElement>(`[data-view="${next}"]`)?.focus();
  }
  return <section className="editor-section" id="editor" aria-label="Редактор решения">
    <OptimizationSummary problem={problem} />
    <div className="panel editor-panel">
      <div className="editor-title">{problem.format === 'optimization' ? <div className="solution-tabs" role="tablist" aria-label="Код оптимизации" onKeyDown={switchCodeTab}><button type="button" role="tab" id="solution-tab" data-view="solution" aria-controls="solution-panel" aria-selected={view === 'solution'} tabIndex={view === 'solution' ? 0 : -1} onClick={() => setView('solution')}>Ваше решение</button><button type="button" role="tab" id="original-tab" data-view="original" aria-controls="original-panel" aria-selected={view === 'original'} tabIndex={view === 'original' ? 0 : -1} onClick={() => setView('original')}>Исходное решение</button></div> : <span className="editor-file-tab active"><span className="file-tab-mark" aria-hidden="true">CU</span>solution.cu</span>}
        <div className="inline"><button type="button" className="text-link" onClick={download}>Скачать код</button><label className="text-link file-button">Выбрать файл<input type="file" accept=".cu,.cpp,.cuh,.h,.txt" aria-label="Загрузить код из файла" onChange={async event => {
          const file = event.target.files?.[0];
          setView('solution');
          event.target.value = '';
          if (!file) return;
          try { changeCode(await file.text()); setUndo({ code, message: 'Файл загружен.' }); } catch { setLocalError(new Error('Не удалось прочитать файл.')); }
        }} /></label></div>
      </div>
      {problem.format === 'optimization' ? <div className="optimization-editors"><div id="solution-panel" role="tabpanel" aria-labelledby="solution-tab" hidden={view !== 'solution'}><CodeEditor fit value={code} onChange={changeCode} status={saved ? 'Черновик сохранен' : 'Хранилище недоступно. Скачайте код.'} /></div><div id="original-panel" role="tabpanel" aria-labelledby="original-tab" hidden={view !== 'original'}><CodeEditor fit value={problem.starterCode} readOnly label="Исходное решение" status="Исходный код задачи" /></div></div> : <CodeEditor fit value={code} onChange={changeCode} status={saved ? 'Черновик сохранен' : 'Хранилище недоступно. Скачайте код.'} />}
      <div className="editor-footer" ref={feedbackRef}>
      {(undo || error || check.isPending || example) && <div className="editor-feedback">
        {!busy && Boolean(error || example) && <button type="button" className="close-output" aria-label="Закрыть вывод" title="Закрыть вывод" onClick={closeOutput}><svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg></button>}
        {undo !== null && <div className="small">{undo.message} <button type="button" className="text-link" onClick={() => changeCode(undo.code)}>Отменить</button></div>}
        <ErrorMessage error={error} />
        {(check.isPending || example) && <section className="run-console" aria-label="Вывод проверки"><div className="tool-window-body">
          {check.isPending ? <p role="status">Проверяем пример...</p> : example && (example.code !== code ? <p className="muted" role="status">Код изменен. Проверьте пример снова.</p> : <><p role="status" className={example.passed ? 'success' : 'error'}>{example.passed ? 'Пример пройден.' : 'Пример не пройден.'}</p>{example.diagnostic && <pre className="diagnostic" tabIndex={0} aria-label="Диагностика примера">{example.diagnostic}</pre>}</>)}
        </div></section>}
      </div>}
      <div className="editor-actions">
        {view === 'original' ? <button type="button" className="btn primary" onClick={() => setView('solution')}>Продолжить редактирование</button> : <>
        <button type="button" className="text-link" onClick={reset} disabled={busy}>{problem.format === 'optimization' ? 'Восстановить исходный код' : 'Восстановить шаблон'}</button>
        <button type="button" className="btn secondary run-btn" onClick={() => { clearErrors(); setExample(null); check.mutate(code); }} disabled={busy || !code.trim()}>{check.isPending ? 'Проверяем пример...' : 'Проверить пример'}</button>
        {phase === 'finished' ? <button type="button" className="btn primary" onClick={practice}>Продолжить в практике</button>
          : phase === 'upcoming' ? <button type="button" className="btn primary" disabled>Отправка после начала</button>
          : !user ? <button type="button" className="btn primary" onClick={requestLogin}>Войти и отправить</button>
          : contest?.format === 'duel' && !canJoinContest(contest, user.id) ? <button type="button" className="btn primary" disabled>Только для участников дуэли</button>
          : context !== 'practice' && !contest?.registered ? <button type="button" className="btn primary" disabled={busy || contestLoading} onClick={() => { clearErrors(); join.mutate(); }}>{join.isPending ? 'Регистрируем...' : contest?.format === 'duel' ? 'Принять дуэль' : 'Участвовать'}</button>
          : <button type="button" className="btn primary" disabled={busy || !code.trim() || contestLoading} onClick={() => { clearErrors(); submit.mutate(); }}>{submit.isPending ? 'Отправляем...' : 'Отправить'}</button>}
        </>}
      </div>
      </div>
    </div>
  </section>;
}

function GuessPane({ problem, context, contest, phase, contestLoading }: { problem: ProblemDetail; context: string; contest?: Contest; phase: ReturnType<typeof phaseOf>; contestLoading: boolean }) {
  const { user } = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<GuessInput['choice'] | ''>('');
  const [answer, setAnswer] = useState<Submission | null>(null);
  const benchmarkKey = problem.referenceMeasurement?.key;
  const referencesReady = problem.referenceMeasurement?.status === 'ready' && Boolean(benchmarkKey);
  const previous = useQuery({
    queryKey: ['submission', problem.guessSubmissionId, user?.id],
    queryFn: ({ signal }) => api.getSubmission(problem.guessSubmissionId!, signal),
    enabled: referencesReady && Boolean(user && problem.guessSubmissionId),
  });
  const saved = referencesReady ? answer ?? previous.data : undefined;
  const guess = useMutation({
    mutationFn: (value: GuessInput['choice']) => {
      if (!benchmarkKey) throw new ApiError(503, 'Эталонные измерения еще не готовы.');
      return api.submitGuess(problem.id, context, { choice: value, benchmarkKey });
    },
    onSuccess: result => { setAnswer(result); void queryClient.invalidateQueries(); },
    onError: error => {
      if (error instanceof ApiError && error.status === 401) navigate(`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`);
      if (error instanceof ApiError && error.status === 400) void queryClient.invalidateQueries({ queryKey: ['problem', problem.id, context] });
    },
  });
  const join = useMutation({
    mutationFn: () => api.registerForContest(context),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['contest', context] }),
  });
  const feedbackRef = useFeedbackVisibility(saved || guess.error || join.error);
  const blocked = !referencesReady || guess.isPending || join.isPending || Boolean(saved) || Boolean(user && problem.guessSubmissionId && !previous.data) || contestLoading || phase === 'upcoming' || phase === 'finished';
  return <section className="guess-pane" id="editor" aria-label="Выберите более быстрый код">
    {!referencesReady && <ReferenceStatus problem={problem} />}
    <fieldset className="guess-comparison" disabled={blocked}><legend className="visually-hidden">Выберите более быстрый код</legend>
      <div className="guess-codes">{[problem.firstCode, problem.secondCode].map((code, index) => {
        const value = index === 0 ? 'first' : 'second';
        const selected = (saved?.guessResult?.choice ?? choice) === value;
        const correct = saved?.guessResult?.correctChoice === value;
        return <section className={`panel editor-panel guess-code-card${selected ? ' selected' : ''}${saved ? correct ? ' answer-correct' : selected ? ' answer-wrong' : '' : ''}`} key={value}>
          <div className="guess-code-heading"><h3>Вариант {index + 1}</h3></div>
          <CodeEditor value={code ?? ''} readOnly label={`Код ${index + 1}`} />
          <label className="guess-pick"><input type="radio" name="guess" value={value} checked={selected} onChange={() => setChoice(value)} /><span className="guess-pick-mark" aria-hidden="true" /><span>{guessLabels[value]}</span></label>
        </section>;
      })}</div>
      <label className={`guess-equal guess-pick${saved?.guessResult?.correctChoice === 'equal' ? ' answer-correct' : ''}`}><input type="radio" name="guess" value="equal" aria-label="Одинаково" checked={(saved?.guessResult?.choice ?? choice) === 'equal'} onChange={() => setChoice('equal')} /><span className="guess-equal-symbol" aria-hidden="true">=</span><span><strong>Одинаково</strong></span><span className="guess-pick-mark" aria-hidden="true" /></label>
    </fieldset>
    {phase === 'finished' ? <Link className="btn secondary" to={problemPath(problem.id)}>Продолжить в практике</Link>
      : phase === 'upcoming' ? <p className="muted">Ответы принимаются после начала.</p>
      : !user ? <Link className="btn primary" to={`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`}>Войти и ответить</Link>
      : contest?.format === 'duel' && !canJoinContest(contest, user.id) ? <p className="muted">Ответить могут только участники дуэли.</p>
      : context !== 'practice' && !contest?.registered ? <button className="btn primary" disabled={blocked} onClick={() => join.mutate()}>{join.isPending ? 'Регистрируем...' : contest?.format === 'duel' ? 'Принять дуэль' : 'Участвовать'}</button>
      : !saved && <button className="btn primary guess-submit" disabled={blocked || !choice} onClick={() => { if (choice) guess.mutate(choice); }}>{guess.isPending ? 'Проверяем ответ...' : 'Проверить выбор'}</button>}
    {!saved && referencesReady && phase !== 'finished' && <p className="small muted guess-attempt-note">Одна попытка. После проверки откроется разбор.</p>}
    <div ref={feedbackRef}><ErrorMessage error={guess.error || join.error || previous.error} />
      {referencesReady && previous.isFetching && !saved && <p role="status">Загрузка предыдущего ответа...</p>}
      {referencesReady && previous.error && <button className="text-link" onClick={() => void previous.refetch()}>Повторить загрузку ответа</button>}
      {saved?.guessResult && <GuessResult result={saved.guessResult} />}
    </div>
  </section>;
}

function OptimizationSummary({ problem }: { problem: ProblemDetail }) {
  if (problem.format !== 'optimization') return null;
  const ready = problem.referenceMeasurement?.status === 'ready';
  const baseline = ready && problem.baselineTimeMs != null && problem.baselineTimeMs > 0 ? problem.baselineTimeMs : null;
  const speedup = baseline != null && problem.bestTimeMs != null && problem.bestTimeMs > 0 ? baseline / problem.bestTimeMs : null;
  return <section className="optimization-summary" aria-label="Результаты оптимизации">
    {!ready ? <ReferenceStatus problem={problem} /> : baseline != null ? <dl className="optimization-metrics"><div><dt>Исходное решение</dt><dd><TimeValue value={baseline} /><span> мс</span></dd></div><div><dt>Ваш лучший результат</dt><dd>{problem.bestTimeMs != null ? <><TimeValue value={problem.bestTimeMs} /><span> мс</span></> : <span className="metric-empty">Нет посылок</span>}</dd></div>{speedup != null && <div className={speedup > 1 ? 'metric-improved' : ''}><dt>Ускорение</dt><dd>{speedup.toFixed(3)}x</dd></div>}</dl> : <p className="small muted">Время исходного решения недоступно.</p>}
  </section>;
}

function Leaders({ problemId, context, finished }: { problemId: string; context: string; finished: boolean }) {
  const { user } = useSession();
  const leaders = useQuery({
    queryKey: ['problem-leaders', problemId, context, user?.id],
    queryFn: ({ signal }) => api.problemLeaders(problemId, context, signal),
    refetchInterval: finished ? false : 5000,
  });
  return <QueryState query={leaders}>{leaders.data && (leaders.data.length ? <div className="panel table-wrap"><table className="data-table" aria-label="Лидеры задачи">
    <thead><tr><th>Место</th><th>Участник</th><th>Лучшее время, мс</th><th>Посылка</th></tr></thead>
    <tbody>{leaders.data.map(item => <tr key={item.user.id} className={item.user.id === user?.id ? 'own-row' : undefined}><td>{item.rank}</td><td><UserLink user={item.user} /></td><td className="mono"><TimeValue value={item.timeMs} /></td><td><Link to={`/submission/${encodeURIComponent(item.submissionId)}`}>#{item.submissionId}</Link></td></tr>)}</tbody>
  </table></div> : <Empty>Принятых решений в этой конфигурации пока нет.</Empty>)}</QueryState>;
}

export default function Problem() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const { user, isLoading } = useSession();
  const queryClient = useQueryClient();
  const statementRef = useRef<HTMLElement>(null);
  const [now, setNow] = useState(Date.now);
  const previousPhase = useRef<{ context: string; phase: ReturnType<typeof phaseOf> } | null>(null);
  const context = contextFromSearch(params);
  function returnToStatement() {
    requestAnimationFrame(() => {
      const statement = statementRef.current;
      if (!statement) return;
      statement.focus({ preventScroll: true });
      const rect = statement.getBoundingClientRect();
      const viewport = window.visualViewport;
      const top = (viewport?.offsetTop ?? 0) + 8;
      const bottom = (viewport?.offsetTop ?? 0) + (viewport?.height ?? window.innerHeight) - 8;
      if (rect.height > 0 && (rect.top < top || rect.top >= bottom)) {
        statement.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' });
      }
    });
  }
  useEffect(() => {
    if (context === 'practice') return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [context]);
  const requestedTab = ['submissions', 'leaders'].includes(params.get('tab') || '') ? params.get('tab')! : 'statement';
  const problem = useQuery({
    queryKey: ['problem', id, context, user?.id],
    queryFn: ({ signal }) => api.getProblem(id, context, signal),
    enabled: Boolean(id) && !isLoading,
    refetchInterval: query => ['pending', 'running'].includes(query.state.data?.referenceMeasurement?.status ?? '') ? 5000 : false,
  });
  const contest = useQuery({
    queryKey: ['contest', context, user?.id],
    queryFn: ({ signal }) => api.getContest(context, signal),
    enabled: context !== 'practice' && !isLoading,
    refetchInterval: q => q.state.data && Date.now() >= Date.parse(q.state.data.endAt) && !q.state.data.resultsPending ? false : 5000,
  });
  const phase = phaseOf(contest.data, now);
  const isGuess = problem.data?.format === 'guess';
  const isDuel = contest.data?.format === 'duel';
  const tab = (isGuess || isDuel) && requestedTab === 'leaders' ? 'statement' : requestedTab;
  useEffect(() => {
    const previous = previousPhase.current;
    if (phase && previous?.context === context && previous.phase && previous.phase !== phase) {
      void queryClient.invalidateQueries({ queryKey: ['contest', context] });
      void queryClient.invalidateQueries({ queryKey: ['problem', id, context] });
      void queryClient.invalidateQueries({ queryKey: ['problem-leaders', id, context] });
      void queryClient.invalidateQueries({ queryKey: ['duel-gpu', context] });
      void queryClient.invalidateQueries({ queryKey: ['standings', context] });
    }
    previousPhase.current = { context, phase };
  }, [phase, context, id, queryClient]);
  return <QueryState query={problem}>{problem.data && <>
    <div className="breadcrumbs"><Link to={context === 'practice' ? '/problems' : `/contest/${encodeURIComponent(context)}`}>{context === 'practice' ? 'Практика' : contest.data?.title || 'Контест'}</Link></div>
    <PageHeader title={`${problem.data.code}. ${problem.data.title}`} actions={tab === 'statement' ? <a className="text-link editor-anchor" href="#editor">{isGuess ? 'К вариантам ответа' : 'К редактору'}</a> : undefined} />
    <div className="problem-meta"><span>{formatLabels[problem.data.format ?? 'standard']}</span><span>{difficultyLabels[problem.data.difficulty]}</span>{!isGuess && <><span>Лимит: {problem.data.timeLimitSeconds} с</span><span>Память: {problem.data.memoryLimitMb} МБ</span></>}</div>
    <Tabs selected={tab} items={[
      { id: 'statement', label: isGuess ? 'Условие и ответ' : 'Условие и решение', to: problemPath(id, context) },
      { id: 'submissions', label: isGuess ? 'История ответов' : 'Мои посылки', to: problemPath(id, context, 'submissions') },
      ...(!isGuess && !isDuel ? [{ id: 'leaders', label: 'Лидеры', to: problemPath(id, context, 'leaders') }] : []),
    ]} />
    {context !== 'practice' && contest.error && <ErrorMessage error={contest.error} />}
    {phase === 'finished' && !isDuel && <p className="notice">Соревнование завершено. Новые решения в практике не изменят его результаты.</p>}
    {isDuel && contest.data && <DuelBoard contest={contest.data} finished={phase === 'finished'} contestUpdatedAt={contest.dataUpdatedAt} />}
    {tab === 'statement' ? <div className={isGuess ? 'guess-workspace' : 'problem-workspace'}>
      <section className="statement statement-pane" id="statement" ref={statementRef} tabIndex={-1} aria-label="Условие задачи"><div className="tool-window-head">Условие</div><div className="statement-body"><Markdown>{problem.data.statementMarkdown}</Markdown><BenchmarkInfo benchmark={problem.data.benchmark} /></div></section>
      {isGuess ? <GuessPane key={`${user?.id || 'guest'}:${id}:${context}:${problem.data.versionId ?? ''}:${problem.data.referenceMeasurement?.key ?? ''}`} problem={problem.data} context={context} contest={contest.data} phase={phase} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error))} /> : <EditorPane key={`${user?.id || 'guest'}:${id}:${context}`} problem={problem.data} context={context} contest={contest.data} phase={phase} onCloseOutput={returnToStatement} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error))} />}
    </div> : tab === 'submissions' ? <SubmissionList key={`${id}:${context}:${user?.id || 'guest'}`} problemId={id} context={context} /> : <Leaders problemId={id} context={context} finished={phase === 'finished'} />}
  </>}</QueryState>;
}
