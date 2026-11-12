import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, ApiError } from '../api/client';
import type { Contest, GuessInput, ProblemDetail, ProblemProcessor, ProblemProcessorDetail, Submission } from '../api/types';
import { useSession } from '../auth';
import { useAuthDialog } from '../auth-dialog';
import CodeEditor from '../components/CodeEditor';
import GuessResult from '../components/GuessResult';
import DuelBoard from '../components/DuelBoard';
import BenchmarkInfo from '../components/BenchmarkInfo';
import ReferenceStatus from '../components/ReferenceStatus';
import SplitWorkspace from '../components/SplitWorkspace';
import { readDraft, rememberEditorLogin, writeDraft, type DraftTarget } from '../drafts';
import { formatLimit, scoreUnits, useProcessors } from '../processors';
import { Difficulty, Empty, ErrorMessage, Markdown, PageHeader, QueryState, ScoreValue, Tabs, UserLink, useFeedbackVisibility } from '../ui';
import { canJoinContest, contextFromSearch, difficultyLabels, formatLabels, guessLabels, problemPath, shortId } from '../utils';
import { SubmissionList } from './Submissions';
import { t } from '../i18n';

const GuessWorkspace = ({ children }: { children: [ReactNode, ReactNode] }) => <div className="guess-workspace">{children}</div>;

function phaseOf(contest: Contest | undefined, now: number) {
  if (!contest) return null;
  return now < Date.parse(contest.startAt) ? 'upcoming' : now >= Date.parse(contest.endAt) ? 'finished' : 'live';
}

type Target = { processor: string; language: string };

function EditorPane({ problem, detail, summary, target, context, contest, contestLoading, phase, onCloseOutput }: {
  problem: ProblemDetail;
  detail: ProblemProcessorDetail;
  summary: ProblemProcessor;
  target: Target;
  context: string;
  contest?: Contest;
  contestLoading: boolean;
  phase: ReturnType<typeof phaseOf>;
  onCloseOutput: () => void;
}) {
  const { user } = useSession();
  const openAuth = useAuthDialog();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const processors = useProcessors();
  const draft: DraftTarget = { problemId: problem.id, context, ...target };
  // an optimization starts from the problem's working solution on this processor
  const starterCode = detail.starters[target.language] ?? '';
  const extension = processors.language(target.processor, target.language)?.extension ?? '.txt';
  const fileName = `solution${extension}`;
  const [code, setCode] = useState(() => readDraft(user?.id || null, draft, starterCode));
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
    setSaved(writeDraft(user?.id || null, draft, value));
    clearErrors();
    setUndo(null);
  }
  function requestLogin() {
    rememberEditorLogin(draft, code, location.pathname + location.search);
    openAuth('login', location.pathname + location.search);
  }
  const check = useMutation({
    mutationFn: (snapshot: string) => api.checkExample(problem.id, context, { code: snapshot, ...target }),
    onSuccess: (result, snapshot) => setExample({ code: snapshot, ...result }),
    onError: error => { if (error instanceof ApiError && error.status === 401) requestLogin(); },
  });
  const submit = useMutation({
    mutationFn: () => api.submitSolution({ problemId: problem.id, ...(context.includes(':') ? { context } : context !== 'practice' ? { contestId: context } : {}), ...target, code }),
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
    changeCode(starterCode);
    setUndo({ code, message: problem.format === 'optimization' ? t('Исходный код восстановлен.') : t('Шаблон восстановлен.') });
    setExample(null);
    check.reset();
  }
  function practice() {
    const nextContext = contest?.finalized && contest.format !== 'duel' ? `upsolve:${contest.id}` : 'practice';
    setSaved(writeDraft(user?.id || null, { ...draft, context: nextContext }, code));
    navigate(problemPath(problem.id, nextContext, undefined, target));

  }
  function download() {
    const url = URL.createObjectURL(new Blob([view === 'original' ? starterCode : code], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
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
  return <section className="editor-section" id="editor" aria-label={t('Редактор решения')}>
    <OptimizationSummary problem={problem} processor={summary} />
    <div className="panel editor-panel">
      <div className="editor-title">{problem.format === 'optimization' ? <div className="solution-tabs" role="tablist" aria-label={t('Код оптимизации')} onKeyDown={switchCodeTab}><button type="button" role="tab" id="solution-tab" data-view="solution" aria-controls="solution-panel" aria-selected={view === 'solution'} tabIndex={view === 'solution' ? 0 : -1} onClick={() => setView('solution')}>{t('Ваше решение')}</button><button type="button" role="tab" id="original-tab" data-view="original" aria-controls="original-panel" aria-selected={view === 'original'} tabIndex={view === 'original' ? 0 : -1} onClick={() => setView('original')}>{t('Исходное решение')}</button></div> : <span className="editor-file-tab active"><span className="file-tab-mark" aria-hidden="true">{extension.slice(1).toUpperCase().slice(0, 2)}</span>{fileName}</span>}
        <div className="inline"><button type="button" className="text-link" onClick={download}>{t('Скачать код')}</button><label className="text-link file-button">{t('Выбрать файл')}<input type="file" accept={`${extension},.txt`} aria-label={t('Загрузить код из файла')} onChange={async event => {
          const file = event.target.files?.[0];
          setView('solution');
          event.target.value = '';
          if (!file) return;
          try { changeCode(await file.text()); setUndo({ code, message: t('Файл загружен.') }); } catch { setLocalError(new Error(t('Не удалось прочитать файл.'))); }
        }} /></label></div>
      </div>
      {problem.format === 'optimization' ? <div className="optimization-editors"><div id="solution-panel" role="tabpanel" aria-labelledby="solution-tab" hidden={view !== 'solution'}><CodeEditor fit language={target.language} value={code} onChange={changeCode} status={saved ? t('Черновик сохранен') : t('Хранилище недоступно. Скачайте код.')} /></div><div id="original-panel" role="tabpanel" aria-labelledby="original-tab" hidden={view !== 'original'}><CodeEditor fit language={target.language} value={starterCode} readOnly label={t('Исходное решение')} status={t('Исходный код задачи')} /></div></div> : <CodeEditor fit language={target.language} value={code} onChange={changeCode} status={saved ? t('Черновик сохранен') : t('Хранилище недоступно. Скачайте код.')} />}
      <div className="editor-footer" ref={feedbackRef}>
      {(undo || error || check.isPending || example) && <div className="editor-feedback">
        {!busy && Boolean(error || example) && <button type="button" className="close-output" aria-label={t('Закрыть вывод')} title={t('Закрыть вывод')} onClick={closeOutput}><svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg></button>}
        {undo !== null && <div className="small">{undo.message} <button type="button" className="text-link" onClick={() => changeCode(undo.code)}>{t('Отменить')}</button></div>}
        <ErrorMessage error={error} />
        {(check.isPending || example) && <section className="run-console" aria-label={t('Вывод проверки')}><div className="tool-window-body">
          {check.isPending ? <p role="status">{t('Проверяем пример...')}</p> : example && (example.code !== code ? <p className="muted" role="status">{t('Код изменен. Проверьте пример снова.')}</p> : <><p role="status" className={example.passed ? 'success' : 'error'}>{example.passed ? t('Пример пройден.') : t('Пример не пройден.')}</p>{example.diagnostic && <pre className="diagnostic" tabIndex={0} aria-label={t('Диагностика примера')}>{example.diagnostic}</pre>}</>)}
        </div></section>}
      </div>}
      <div className="editor-actions">
        {view === 'original' ? <button type="button" className="btn primary" onClick={() => setView('solution')}>{t('Продолжить редактирование')}</button> : <>
        <button type="button" className="text-link" onClick={reset} disabled={busy}>{problem.format === 'optimization' ? t('Восстановить исходный код') : t('Восстановить шаблон')}</button>
        <button type="button" className="btn secondary run-btn" onClick={() => { clearErrors(); setExample(null); check.mutate(code); }} disabled={busy || !code.trim()}>{check.isPending ? t('Проверяем пример...') : t('Проверить пример')}</button>
        {phase === 'finished' ? <button type="button" className="btn primary" onClick={practice}>{contest?.finalized && contest.format !== 'duel' ? t('Перейти к дорешиванию') : t('Продолжить в практике')}</button>
          : phase === 'upcoming' ? <button type="button" className="btn primary" disabled>{t('Отправка после начала')}</button>
          : !user ? <button type="button" className="btn primary" onClick={requestLogin}>{t('Войти и отправить')}</button>
          : contest?.format === 'duel' && !context.includes(':') && !canJoinContest(contest, user.id) ? <button type="button" className="btn primary" disabled>{t('Только для участников дуэли')}</button>
          : context !== 'practice' && !context.includes(':') && !contest?.registered ? <button type="button" className="btn primary" disabled={busy || contestLoading} onClick={() => { clearErrors(); join.mutate(); }}>{join.isPending ? t('Регистрируем...') : contest?.format === 'duel' ? t('Принять дуэль') : t('Участвовать')}</button>
          : <button type="button" className="btn primary" disabled={busy || !code.trim() || contestLoading} onClick={() => { clearErrors(); submit.mutate(); }}>{submit.isPending ? t('Отправляем...') : t('Отправить')}</button>}
        </>}
      </div>
      </div>
    </div>
  </section>;
}

/** A guess runs both variants on its only processor: [processor] holds their reference measurements. */
function GuessPane({ problem, processor, context, contest, phase, contestLoading }: { problem: ProblemDetail; processor: ProblemProcessor; context: string; contest?: Contest; phase: ReturnType<typeof phaseOf>; contestLoading: boolean }) {
  const { user } = useSession();
  const location = useLocation();
  const openAuth = useAuthDialog();
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<GuessInput['choice'] | ''>('');
  const [answer, setAnswer] = useState<Submission | null>(null);
  const benchmarkKey = processor.referenceMeasurement?.key;
  const referencesReady = processor.referenceMeasurement?.status === 'ready' && Boolean(benchmarkKey);
  const previous = useQuery({
    queryKey: ['submission', problem.guessSubmissionId, user?.id],
    queryFn: ({ signal }) => api.getSubmission(problem.guessSubmissionId!, signal),
    enabled: referencesReady && Boolean(user && problem.guessSubmissionId),
  });
  const saved = referencesReady ? answer ?? previous.data : undefined;
  const guess = useMutation({
    mutationFn: (value: GuessInput['choice']) => {
      if (!benchmarkKey) throw new ApiError(503, t('Эталонные измерения еще не готовы.'));
      return api.submitGuess(problem.id, context, { choice: value, benchmarkKey });
    },
    onSuccess: result => { setAnswer(result); void queryClient.invalidateQueries(); },
    onError: error => {
      if (error instanceof ApiError && error.status === 401) openAuth('login', location.pathname + location.search);
      if (error instanceof ApiError && error.status === 400) void queryClient.invalidateQueries({ queryKey: ['problem', problem.id, context] });
    },
  });
  const join = useMutation({
    mutationFn: () => api.registerForContest(context),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['contest', context] }),
  });
  const feedbackRef = useFeedbackVisibility(saved || guess.error || join.error);
  const blocked = !referencesReady || guess.isPending || join.isPending || Boolean(saved) || Boolean(user && problem.guessSubmissionId && !previous.data) || contestLoading || phase === 'upcoming' || phase === 'finished';
  return <section className="guess-pane" id="editor" aria-label={t('Выберите более быстрый код')}>
    {!referencesReady && <ReferenceStatus format={problem.format} measurement={processor.referenceMeasurement} />}
    <fieldset className="guess-comparison" disabled={blocked}><legend className="visually-hidden">{t('Выберите более быстрый код')}</legend>
      <div className="guess-codes">{[problem.firstCode, problem.secondCode].map((code, index) => {
        const value = index === 0 ? 'first' : 'second';
        const selected = (saved?.guessResult?.choice ?? choice) === value;
        const correct = saved?.guessResult?.correctChoice === value;
        return <section className={`panel editor-panel guess-code-card${selected ? ' selected' : ''}${saved ? correct ? ' answer-correct' : selected ? ' answer-wrong' : '' : ''}`} key={value}>
          <div className="guess-code-heading"><h3>{t('Вариант')} {index + 1}</h3></div>
          <CodeEditor value={code ?? ''} language={processor.languages[0]} readOnly label={t('Код {0}', [index + 1])} />
          <label className="guess-pick"><input type="radio" name="guess" value={value} checked={selected} onChange={() => setChoice(value)} /><span className="guess-pick-mark" aria-hidden="true" /><span>{guessLabels[value]}</span></label>
        </section>;
      })}</div>
      <label className={`guess-equal guess-pick${saved?.guessResult?.correctChoice === 'equal' ? ' answer-correct' : ''}`}><input type="radio" name="guess" value="equal" aria-label={t('Одинаково')} checked={(saved?.guessResult?.choice ?? choice) === 'equal'} onChange={() => setChoice('equal')} /><span className="guess-equal-symbol" aria-hidden="true">=</span><span><strong>{t('Одинаково')}</strong></span><span className="guess-pick-mark" aria-hidden="true" /></label>
    </fieldset>
    {phase === 'finished' ? <Link className="btn secondary" to={problemPath(problem.id, contest?.finalized && contest.format !== 'duel' ? `upsolve:${contest.id}` : 'practice')}>{contest?.finalized && contest.format !== 'duel' ? t('Перейти к дорешиванию') : t('Продолжить в практике')}</Link>
      : phase === 'upcoming' ? <p className="muted">{t('Ответы принимаются после начала.')}</p>
      : !user ? <button className="btn primary" onClick={() => openAuth('login', location.pathname + location.search)}>{t('Войти и ответить')}</button>
      : contest?.format === 'duel' && !context.includes(':') && !canJoinContest(contest, user.id) ? <p className="muted">{t('Ответить могут только участники дуэли.')}</p>
      : context !== 'practice' && !context.includes(':') && !contest?.registered ? <button className="btn primary" disabled={blocked} onClick={() => join.mutate()}>{join.isPending ? t('Регистрируем...') : contest?.format === 'duel' ? t('Принять дуэль') : t('Участвовать')}</button>
      : !saved && <button className="btn primary guess-submit" disabled={blocked || !choice} onClick={() => { if (choice) guess.mutate(choice); }}>{guess.isPending ? t('Проверяем ответ...') : t('Проверить выбор')}</button>}
    {!saved && referencesReady && phase !== 'finished' && <p className="small muted guess-attempt-note">{t('Одна попытка. После проверки откроется разбор.')}</p>}
    <div ref={feedbackRef}><ErrorMessage error={guess.error || join.error || previous.error} />
      {referencesReady && previous.isFetching && !saved && <p role="status">{t('Загрузка предыдущего ответа...')}</p>}
      {referencesReady && previous.error && <button className="text-link" onClick={() => void previous.refetch()}>{t('Повторить загрузку ответа')}</button>}
      {saved?.guessResult && <GuessResult result={saved.guessResult} />}
    </div>
  </section>;
}

/** Baseline, personal best and improvement of an optimization on the chosen [processor], in its score. */
function OptimizationSummary({ problem, processor }: { problem: ProblemDetail; processor: ProblemProcessor }) {
  if (problem.format !== 'optimization') return null;
  const ready = processor.referenceMeasurement?.status === 'ready';
  const baseline = ready && processor.baselineScore && processor.baselineScore.value > 0 ? processor.baselineScore : null;
  const best = processor.bestScore;
  const speedup = baseline && best && best.value > 0 ? baseline.value / best.value : null;
  return <section className="optimization-summary" aria-label={t('Результаты оптимизации')}>
    {!ready ? <ReferenceStatus format={problem.format} measurement={processor.referenceMeasurement} /> : baseline ? <dl className="optimization-metrics"><div><dt>{t('Исходное решение')}</dt><dd><ScoreValue score={baseline} /></dd></div><div><dt>{t('Ваш лучший результат')}</dt><dd>{best ? <ScoreValue score={best} /> : <span className="metric-empty">{t('Нет посылок')}</span>}</dd></div>{speedup != null && <div className={speedup > 1 ? 'metric-improved' : ''}><dt>{t('Улучшение')}</dt><dd>{speedup.toFixed(3)}x</dd></div>}</dl> : <p className="small muted">{t('Результат исходного решения недоступен.')}</p>}
  </section>;
}

function Leaders({ problemId, processor, context, finished }: { problemId: string; processor: string; context: string; finished: boolean }) {
  const { user } = useSession();
  const processors = useProcessors();
  const leaders = useQuery({
    queryKey: ['problem-leaders', problemId, context, processor, user?.id],
    queryFn: ({ signal }) => api.problemLeaders(problemId, processor, context, signal),
    refetchInterval: finished ? false : 5000,
  });
  return <QueryState query={leaders}>{leaders.data && (leaders.data.length ? <div className="panel table-wrap"><table className="data-table" aria-label={t('Лидеры задачи')}>
    <thead><tr><th className="num">{t('Место')}</th><th>{t('Участник')}</th><th className="num">{t('Лучший результат')}</th><th className="num">{t('Посылка')}</th></tr></thead>
    <tbody>{leaders.data.map(item => <tr key={item.user.id} className={item.user.id === user?.id ? 'own-row' : undefined}><td className="num">{item.rank}</td><td><UserLink user={item.user} /></td><td className="num mono"><ScoreValue score={item.score} /></td><td className="num mono"><Link to={`/submission/${encodeURIComponent(item.submissionId)}`} title={item.submissionId}>#{shortId(item.submissionId)}</Link></td></tr>)}</tbody>
  </table></div> : <Empty>{t('Принятых решений на процессоре')} {processors.title(processor)} {t('в этой конфигурации пока нет.')}</Empty>)}</QueryState>;
}

/** The processor and language the page works with: from the address, else the first the problem offers. */
function chooseTarget(problem: ProblemDetail, params: URLSearchParams): Target | null {
  const processor = problem.processors.find(item => item.id === params.get('processor')) ?? problem.processors[0];
  if (!processor) return null;
  const language = processor.languages.find(item => item === params.get('language')) ?? processor.languages[0];
  return language ? { processor: processor.id, language } : null;
}

function TargetPicker({ problem, target, onChange }: { problem: ProblemDetail; target: Target; onChange: (target: Target) => void }) {
  const processors = useProcessors();
  const languages = problem.processors.find(item => item.id === target.processor)?.languages ?? [];
  return <div className="toolbar target-picker">
    {problem.processors.length > 1 ? <label className="field-inline"><span>{t('Процессор')}</span><select aria-label={t('Процессор')} value={target.processor} onChange={event => {
      const next = problem.processors.find(item => item.id === event.target.value);
      if (next) onChange({ processor: next.id, language: next.languages[0] ?? target.language });
    }}>{problem.processors.map(item => <option key={item.id} value={item.id}>{processors.title(item.id)}</option>)}</select></label>
      : <span className="muted">{t('Процессор:')} {processors.title(target.processor)}</span>}
    {languages.length > 1 ? <label className="field-inline"><span>{t('Язык')}</span><select aria-label={t('Язык')} value={target.language} onChange={event => onChange({ ...target, language: event.target.value })}>
      {languages.map(id => <option key={id} value={id}>{processors.language(target.processor, id)?.title ?? id}</option>)}
    </select></label> : <span className="muted">{t('Язык:')} {processors.language(target.processor, target.language)?.title ?? target.language}</span>}
  </div>;
}

export default function Problem() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const { user, isLoading } = useSession();
  const queryClient = useQueryClient();
  const statementRef = useRef<HTMLElement>(null);
  const [now, setNow] = useState(Date.now);
  const previousPhase = useRef<{ context: string; phase: ReturnType<typeof phaseOf> } | null>(null);
  const contestSnapshot = useRef<{ id: string; userId: string | undefined; startedAt: number } | null>(null);
  const context = contextFromSearch(params);
  const virtualId = context.startsWith('virtual:') ? context.slice(8) : null;
  const upsolving = context.startsWith('upsolve:');
  const virtual = useQuery({ queryKey: ['virtual', virtualId, user?.id], queryFn: ({ signal }) => api.getVirtual(virtualId!, signal), enabled: Boolean(virtualId && user), refetchInterval: 5000 });
  const contestId = virtualId ? virtual.data?.contestId : upsolving ? context.slice(8) : context;
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
  const contest = useQuery({
    queryKey: ['contest', contestId, user?.id],
    queryFn: async ({ signal }) => {
      const requestedAt = Date.now();
      const result = await api.getContest(contestId!, signal);
      const startedAt = result.serverNow ? Date.parse(result.serverNow) - Math.max(0, Date.now() - requestedAt) : requestedAt;
      contestSnapshot.current = { id: contestId!, userId: user?.id, startedAt };
      return result;
    },
    enabled: context !== 'practice' && Boolean(contestId) && !isLoading,
    refetchInterval: q => {
      const data = q.state.data;
      const snapshot = contestSnapshot.current;
      if (data?.format !== 'duel' && data?.finalized !== undefined) return data.finalized ? false : 5000;
      return data && data.finalized !== false && !data.resultsPending && snapshot && snapshot.id === contestId && snapshot.userId === user?.id && snapshot.startedAt >= Date.parse(data.endAt) ? false : 5000;
    },

  });
  const problem = useQuery({
    queryKey: ['problem', id, context, user?.id],
    queryFn: ({ signal }) => api.getProblem(id, context, signal),
    enabled: Boolean(id) && !isLoading,
    refetchInterval: query => (query.state.data?.format && query.state.data.format !== 'standard') || (contest.data?.finalized === false && !upsolving) ? 5000 : false,
  });
  const clockOffset = contest.data?.serverNow ? Date.parse(contest.data.serverNow) - contest.dataUpdatedAt : 0;
  const virtualNow = virtual.data ? now + Date.parse(virtual.data.serverNow) - virtual.dataUpdatedAt : now;
  const phase = upsolving ? null : virtualId ? virtual.data ? virtualNow >= Date.parse(virtual.data.endsAt) ? 'finished' : 'live' : 'upcoming' : phaseOf(contest.data, now + clockOffset);
  const isGuess = problem.data?.format === 'guess';
  const isDuel = contest.data?.format === 'duel' && !upsolving && !virtualId;
  const tab = (isGuess || isDuel) && requestedTab === 'leaders' ? 'statement' : requestedTab;
  // a guess shows two codes under the statement, there is no border to move
  const Workspace = isGuess ? GuessWorkspace : SplitWorkspace;
  useEffect(() => {
    const previous = previousPhase.current;
    if (phase && previous?.context === context && previous.phase && previous.phase !== phase) {
      void queryClient.invalidateQueries({ queryKey: ['contest', contestId] });
      void queryClient.invalidateQueries({ queryKey: ['problem', id, context] });
      void queryClient.invalidateQueries({ queryKey: ['problem-leaders', id, context] });
      void queryClient.invalidateQueries({ queryKey: ['standings', context] });
    }
    previousPhase.current = { context, phase };
  }, [phase, context, id, contestId, queryClient]);
  const target = problem.data ? chooseTarget(problem.data, params) : null;
  const detail = target ? problem.data?.processorDetails.find(item => item.id === target.processor) : undefined;
  const summary = target ? problem.data?.processors.find(item => item.id === target.processor) : undefined;
  function pickTarget(next: Target) {
    const query = new URLSearchParams(params);
    query.set('processor', next.processor);
    query.set('language', next.language);
    setParams(query, { replace: true });
  }
  // The head is two tight rows, where it is from with the title and limits, then the tabs with the processor: the height goes to the statement and the editor.
  return <QueryState query={problem}>{problem.data && <>
    <div className="problem-top">
      <div className="breadcrumbs"><Link to={context === 'practice' ? '/problems' : virtualId ? `/virtual/${encodeURIComponent(virtualId)}` : `/contest/${encodeURIComponent(contestId || '')}`}>{context === 'practice' ? t('Практика') : contest.data?.title || t('Контест')}</Link>{upsolving && <span> {t('/ Дорешивание')}</span>}{virtualId && <span> {t('/ Виртуальное участие')}</span>}</div>
      <PageHeader title={`${problem.data.code}. ${problem.data.title}`} actions={tab === 'statement' ? <a className="text-link editor-anchor" href="#editor">{isGuess ? t('К вариантам ответа') : t('К редактору')}</a> : undefined} />
      <div className="problem-meta"><span>{formatLabels[problem.data.format ?? 'standard']}</span><span><Difficulty difficulty={problem.data.difficulty} /></span>{!isGuess && <>{detail && <span>{t('Лимит:')} {formatLimit(detail.limit)} {scoreUnits[detail.limit.type]}</span>}<span>{t('Память:')} {problem.data.memoryLimitMb} {t('МБ')}</span></>}</div>
    </div>
    {virtual.error && <div><ErrorMessage error={virtual.error} /><button className="text-link" onClick={() => void virtual.refetch()}>{t('Повторить загрузку участия')}</button></div>}
    {virtual.data && <p className="notice">{t('Виртуальный контест. Осталось')} {Math.max(0, Math.ceil((Date.parse(virtual.data.endsAt) - virtualNow) / 1000))} {t('с.')}</p>}
    <div className="problem-bar">
      <Tabs selected={tab} items={[
        { id: 'statement', label: isGuess ? t('Условие и ответ') : t('Условие и решение'), to: problemPath(id, context, undefined, target ?? undefined) },
        { id: 'submissions', label: isGuess ? t('История ответов') : t('Мои посылки'), to: problemPath(id, context, 'submissions', target ?? undefined) },
        ...(!isGuess && !isDuel ? [{ id: 'leaders', label: t('Лидеры'), to: problemPath(id, context, 'leaders', target ?? undefined) }] : []),
      ]} />
      {target && <TargetPicker problem={problem.data} target={target} onChange={pickTarget} />}
    </div>
    {context !== 'practice' && contest.error && <div><ErrorMessage error={contest.error} /><button className="text-link" disabled={contest.isFetching} onClick={() => void contest.refetch()}>{t('Повторить загрузку соревнования')}</button></div>}
    {phase === 'finished' && !isDuel && <p className="notice">{t('Соревнование завершено. Новые решения в практике не изменят его результаты.')}</p>}
    {isDuel && contest.data && <DuelBoard contest={contest.data} finished={phase === 'finished'} clockOffset={clockOffset} contestUpdatedAt={contestSnapshot.current && contestSnapshot.current.id === contestId && contestSnapshot.current.userId === user?.id ? contestSnapshot.current.startedAt : 0} />}
    {!target || !detail || !summary ? <Empty>{t('Задачу пока нельзя сдавать ни на одном процессоре платформы.')}</Empty> : tab === 'statement' ? <Workspace>
      <section className="statement statement-pane" id="statement" ref={statementRef} tabIndex={-1} aria-label={t('Условие задачи')}><div className="tool-window-head">{t('Условие')}</div><div className="statement-body"><Markdown>{problem.data.statementMarkdown}</Markdown>{problem.data.format === 'standard' ? <details className="methodology"><summary>{t('Среда и замер')}</summary><Markdown>{detail.environmentMarkdown}</Markdown></details> : <BenchmarkInfo benchmark={detail.benchmark} />}</div></section>
      {isGuess ? <GuessPane key={`${user?.id || 'guest'}:${id}:${context}:${problem.data.versionId ?? ''}:${summary.referenceMeasurement?.key ?? ''}`} problem={problem.data} processor={summary} context={context} contest={contest.data} phase={phase} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error) || Boolean(virtualId && (virtual.isPending || virtual.error)))} /> : <EditorPane key={`${user?.id || 'guest'}:${id}:${context}:${target.processor}:${target.language}`} problem={problem.data} detail={detail} summary={summary} target={target} context={context} contest={contest.data} phase={phase} onCloseOutput={returnToStatement} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error) || Boolean(virtualId && (virtual.isPending || virtual.error)))} />}
    </Workspace> : tab === 'submissions' ? <SubmissionList key={`${id}:${context}:${user?.id || 'guest'}`} problemId={id} context={context} /> : <Leaders problemId={id} processor={target.processor} context={context} finished={phase === 'finished' && (virtualId ? !virtual.data?.pending : contest.data?.finalized !== false)} />}

  </>}</QueryState>;
}
