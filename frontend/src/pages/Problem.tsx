import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, ApiError } from '../api/client';
import type { Contest, ProblemDetail } from '../api/types';
import { useSession } from '../auth';
import { useAuthDialog } from '../auth-dialog';
import CodeEditor from '../components/CodeEditor';
import { readDraft, rememberEditorLogin, writeDraft } from '../drafts';
import { Difficulty, Empty, ErrorMessage, Markdown, PageHeader, QueryState, Tabs, TimeValue, UserLink, useFeedbackVisibility } from '../ui';
import { contextFromSearch, problemPath } from '../utils';
import { SubmissionList } from './Submissions';

function phaseOf(contest: Contest | undefined, now: number) {
  if (!contest) return null;
  return now < Date.parse(contest.startAt) ? 'upcoming' : now >= Date.parse(contest.endAt) ? 'finished' : 'live';
}

function EditorPane({ problem, context, contest, contestLoading, phase, onCloseOutput }: { problem: ProblemDetail; context: string; contest?: Contest; contestLoading: boolean; phase: ReturnType<typeof phaseOf>; onCloseOutput: () => void }) {
  const { user } = useSession();
  const openAuth = useAuthDialog();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [code, setCode] = useState(() => readDraft(user?.id || null, problem.id, context, problem.starterCode));
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
    openAuth('login', location.pathname + location.search);
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
    setUndo({ code, message: 'Шаблон восстановлен.' });
    setExample(null);
    check.reset();
  }
  function practice() {
    setSaved(writeDraft(user?.id || null, problem.id, 'practice', code));
    navigate(problemPath(problem.id));
  }
  function download() {
    const url = URL.createObjectURL(new Blob([code], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'solution.cu';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section className="editor-section" id="editor" aria-label="Редактор решения">
    <div className="panel editor-panel">
      <div className="editor-title"><span className="editor-file-tab active"><span className="file-tab-mark" aria-hidden="true">CU</span>solution.cu</span>
        <div className="inline"><button type="button" className="text-link" onClick={download}>Скачать код</button><label className="text-link file-button">Выбрать файл<input type="file" accept=".cu,.cpp,.cuh,.h,.txt" aria-label="Загрузить код из файла" onChange={async event => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (!file) return;
          try { changeCode(await file.text()); setUndo({ code, message: 'Файл загружен.' }); } catch { setLocalError(new Error('Не удалось прочитать файл.')); }
        }} /></label></div>
      </div>
      <CodeEditor fit value={code} onChange={changeCode} status={saved ? 'Черновик сохранен' : 'Хранилище недоступно. Скачайте код.'} />
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
        <button type="button" className="text-link" onClick={reset} disabled={busy}>Восстановить шаблон</button>
        <button type="button" className="btn secondary run-btn" onClick={() => { clearErrors(); setExample(null); check.mutate(code); }} disabled={busy || !code.trim()}>{check.isPending ? 'Проверяем пример...' : 'Проверить пример'}</button>
        {phase === 'finished' ? <button type="button" className="btn primary" onClick={practice}>Продолжить в практике</button>
          : phase === 'upcoming' ? <button type="button" className="btn primary" disabled>Отправка после начала</button>
          : !user ? <button type="button" className="btn primary" onClick={requestLogin}>Войти и отправить</button>
          : context !== 'practice' && !contest?.registered ? <button type="button" className="btn primary" disabled={busy || contestLoading} onClick={() => { clearErrors(); join.mutate(); }}>{join.isPending ? 'Регистрируем...' : 'Участвовать'}</button>
          : <button type="button" className="btn primary" disabled={busy || !code.trim() || contestLoading} onClick={() => { clearErrors(); submit.mutate(); }}>{submit.isPending ? 'Отправляем...' : 'Отправить'}</button>}
      </div>
      </div>
    </div>
    <a href="#statement" className="back-to-statement">К условию</a>
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
  const tab = ['submissions', 'leaders'].includes(params.get('tab') || '') ? params.get('tab')! : 'statement';
  const problem = useQuery({
    queryKey: ['problem', id, context, user?.id],
    queryFn: ({ signal }) => api.getProblem(id, context, signal),
    enabled: Boolean(id) && !isLoading,
  });
  const contest = useQuery({
    queryKey: ['contest', context, user?.id],
    queryFn: ({ signal }) => api.getContest(context, signal),
    enabled: context !== 'practice' && !isLoading,
    refetchInterval: q => q.state.data && Date.now() >= Date.parse(q.state.data.endAt) ? false : 5000,
  });
  const phase = phaseOf(contest.data, now);
  useEffect(() => {
    const previous = previousPhase.current;
    if (phase && previous?.context === context && previous.phase && previous.phase !== phase) {
      void queryClient.invalidateQueries({ queryKey: ['contest', context] });
      void queryClient.invalidateQueries({ queryKey: ['problem', id, context] });
      void queryClient.invalidateQueries({ queryKey: ['problem-leaders', id, context] });
    }
    previousPhase.current = { context, phase };
  }, [phase, context, id, queryClient]);
  return <QueryState query={problem}>{problem.data && <>
    <div className="breadcrumbs"><Link to={context === 'practice' ? '/problems' : `/contest/${encodeURIComponent(context)}`}>{context === 'practice' ? 'Практика' : contest.data?.title || 'Контест'}</Link></div>
    <PageHeader title={`${problem.data.code}. ${problem.data.title}`} actions={tab === 'statement' ? <a className="text-link editor-anchor" href="#editor">К редактору</a> : undefined} />
    <div className="problem-meta"><span><Difficulty difficulty={problem.data.difficulty} /></span><span>Время: {problem.data.timeLimitSeconds} с</span><span>Память GPU: {problem.data.memoryLimitMb} MB</span></div>
    <Tabs selected={tab} items={[
      { id: 'statement', label: 'Условие и решение', to: problemPath(id, context) },
      { id: 'submissions', label: context === 'practice' ? 'Мои посылки' : 'Мои посылки в контесте', to: problemPath(id, context, 'submissions') },
      { id: 'leaders', label: 'Лидеры', to: problemPath(id, context, 'leaders') },
    ]} />
    {context !== 'practice' && contest.error && <ErrorMessage error={contest.error} />}
    {phase === 'finished' && <p className="notice">Соревнование завершено. Новые решения в практике не изменят его результаты.</p>}
    {tab === 'statement' ? <div className="problem-workspace">
      <section className="statement statement-pane" id="statement" ref={statementRef} tabIndex={-1} aria-label="Условие задачи"><div className="tool-window-head">Условие</div><div className="statement-body"><Markdown>{problem.data.statementMarkdown}</Markdown><details className="methodology"><summary>Среда и замер</summary><Markdown>{problem.data.environmentMarkdown}</Markdown></details></div></section>
      <EditorPane key={`${user?.id || 'guest'}:${id}:${context}`} problem={problem.data} context={context} contest={contest.data} phase={phase} onCloseOutput={returnToStatement} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error))} />
    </div> : tab === 'submissions' ? <SubmissionList key={`${id}:${context}:${user?.id || 'guest'}`} problemId={id} context={context} /> : <Leaders problemId={id} context={context} finished={phase === 'finished'} />}
  </>}</QueryState>;
}
