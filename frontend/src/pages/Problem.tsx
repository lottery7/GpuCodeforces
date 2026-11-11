import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, ApiError } from '../api/client';
import type { Contest, ProblemDetail, ProblemProcessorDetail } from '../api/types';
import { useSession } from '../auth';
import CodeEditor from '../components/CodeEditor';
import { readDraft, rememberEditorLogin, writeDraft, type DraftTarget } from '../drafts';
import { formatLimit, limitLabels, scoreUnits, useProcessors } from '../processors';
import { Empty, ErrorMessage, Markdown, PageHeader, QueryState, ScoreValue, Tabs, UserLink, useFeedbackVisibility } from '../ui';
import { contextFromSearch, difficultyLabels, problemPath } from '../utils';
import { SubmissionList } from './Submissions';

function phaseOf(contest: Contest | undefined, now: number) {
  if (!contest) return null;
  return now < Date.parse(contest.startAt) ? 'upcoming' : now >= Date.parse(contest.endAt) ? 'finished' : 'live';
}

type Target = { processor: string; language: string };

function EditorPane({ problem, detail, target, context, contest, contestLoading, phase, onCloseOutput }: {
  problem: ProblemDetail;
  detail: ProblemProcessorDetail;
  target: Target;
  context: string;
  contest?: Contest;
  contestLoading: boolean;
  phase: ReturnType<typeof phaseOf>;
  onCloseOutput: () => void;
}) {
  const { user } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const processors = useProcessors();
  const draft: DraftTarget = { problemId: problem.id, context, ...target };
  const starterCode = detail.starters[target.language] ?? '';
  const extension = processors.language(target.processor, target.language)?.extension ?? '.txt';
  const fileName = `solution${extension}`;
  const [code, setCode] = useState(() => readDraft(user?.id || null, draft, starterCode));
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
    navigate(`/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`);
  }
  const check = useMutation({
    mutationFn: (snapshot: string) => api.checkExample(problem.id, context, { code: snapshot, ...target }),
    onSuccess: (result, snapshot) => setExample({ code: snapshot, ...result }),
    onError: error => { if (error instanceof ApiError && error.status === 401) requestLogin(); },
  });
  const submit = useMutation({
    mutationFn: () => api.submitSolution({ problemId: problem.id, ...(context !== 'practice' ? { contestId: context } : {}), ...target, code }),
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
    setUndo({ code, message: 'Шаблон восстановлен.' });
    setExample(null);
    check.reset();
  }
  function practice() {
    setSaved(writeDraft(user?.id || null, { ...draft, context: 'practice' }, code));
    navigate(problemPath(problem.id));
  }
  function download() {
    const url = URL.createObjectURL(new Blob([code], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section className="editor-section" id="editor" aria-label="Редактор решения">
    <div className="panel editor-panel">
      <div className="editor-title"><span className="editor-file-tab active"><span className="file-tab-mark" aria-hidden="true">{extension.slice(1).toUpperCase().slice(0, 2)}</span>{fileName}</span>
        <div className="inline"><button type="button" className="text-link" onClick={download}>Скачать код</button><label className="text-link file-button">Выбрать файл<input type="file" accept={`${extension},.txt`} aria-label="Загрузить код из файла" onChange={async event => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (!file) return;
          try { changeCode(await file.text()); setUndo({ code, message: 'Файл загружен.' }); } catch { setLocalError(new Error('Не удалось прочитать файл.')); }
        }} /></label></div>
      </div>
      <CodeEditor fit language={target.language} value={code} onChange={changeCode} status={saved ? 'Черновик сохранен' : 'Хранилище недоступно. Скачайте код.'} />
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

function Leaders({ problemId, processor, context, finished }: { problemId: string; processor: string; context: string; finished: boolean }) {
  const { user } = useSession();
  const processors = useProcessors();
  const leaders = useQuery({
    queryKey: ['problem-leaders', problemId, context, processor, user?.id],
    queryFn: ({ signal }) => api.problemLeaders(problemId, processor, context, signal),
    refetchInterval: finished ? false : 5000,
  });
  return <QueryState query={leaders}>{leaders.data && (leaders.data.length ? <div className="panel table-wrap"><table className="data-table" aria-label="Лидеры задачи">
    <thead><tr><th>Место</th><th>Участник</th><th>Лучший результат</th><th>Посылка</th></tr></thead>
    <tbody>{leaders.data.map(item => <tr key={item.user.id} className={item.user.id === user?.id ? 'own-row' : undefined}><td>{item.rank}</td><td><UserLink user={item.user} /></td><td className="mono"><ScoreValue score={item.score} /></td><td><Link to={`/submission/${encodeURIComponent(item.submissionId)}`}>#{item.submissionId}</Link></td></tr>)}</tbody>
  </table></div> : <Empty>Принятых решений на процессоре {processors.title(processor)} в этой конфигурации пока нет.</Empty>)}</QueryState>;
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
    {problem.processors.length > 1 ? <label className="field-inline"><span>Процессор</span><select aria-label="Процессор" value={target.processor} onChange={event => {
      const next = problem.processors.find(item => item.id === event.target.value);
      if (next) onChange({ processor: next.id, language: next.languages[0] ?? target.language });
    }}>{problem.processors.map(item => <option key={item.id} value={item.id}>{processors.title(item.id)}</option>)}</select></label>
      : <span className="muted">Процессор: {processors.title(target.processor)}</span>}
    {languages.length > 1 ? <label className="field-inline"><span>Язык</span><select aria-label="Язык" value={target.language} onChange={event => onChange({ ...target, language: event.target.value })}>
      {languages.map(id => <option key={id} value={id}>{processors.language(target.processor, id)?.title ?? id}</option>)}
    </select></label> : <span className="muted">Язык: {processors.language(target.processor, target.language)?.title ?? target.language}</span>}
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
  const target = problem.data ? chooseTarget(problem.data, params) : null;
  const detail = target ? problem.data?.processorDetails.find(item => item.id === target.processor) : undefined;
  function pickTarget(next: Target) {
    const query = new URLSearchParams(params);
    query.set('processor', next.processor);
    query.set('language', next.language);
    setParams(query, { replace: true });
  }
  return <QueryState query={problem}>{problem.data && <>
    <div className="breadcrumbs"><Link to={context === 'practice' ? '/problems' : `/contest/${encodeURIComponent(context)}`}>{context === 'practice' ? 'Практика' : contest.data?.title || 'Контест'}</Link></div>
    <PageHeader title={`${problem.data.code}. ${problem.data.title}`} actions={tab === 'statement' ? <a className="text-link editor-anchor" href="#editor">К редактору</a> : undefined} />
    <div className="problem-meta"><span>{difficultyLabels[problem.data.difficulty]}</span>{detail && <span>{limitLabels[detail.limit.type]}: {formatLimit(detail.limit)} {scoreUnits[detail.limit.type]}</span>}<span>Память: {problem.data.memoryLimitMb} MB</span></div>
    {target && <TargetPicker problem={problem.data} target={target} onChange={pickTarget} />}
    <Tabs selected={tab} items={[
      { id: 'statement', label: 'Условие и решение', to: problemPath(id, context, undefined, target ?? undefined) },
      { id: 'submissions', label: context === 'practice' ? 'Мои посылки' : 'Мои посылки в контесте', to: problemPath(id, context, 'submissions', target ?? undefined) },
      { id: 'leaders', label: 'Лидеры', to: problemPath(id, context, 'leaders', target ?? undefined) },
    ]} />
    {context !== 'practice' && contest.error && <ErrorMessage error={contest.error} />}
    {phase === 'finished' && <p className="notice">Соревнование завершено. Новые решения в практике не изменят его результаты.</p>}
    {!target || !detail ? <Empty>Задачу пока нельзя сдавать ни на одном процессоре платформы.</Empty> : tab === 'statement' ? <div className="problem-workspace">
      <section className="statement statement-pane" id="statement" ref={statementRef} tabIndex={-1} aria-label="Условие задачи"><div className="tool-window-head">Условие</div><div className="statement-body"><Markdown>{problem.data.statementMarkdown}</Markdown><details className="methodology"><summary>Среда и замер</summary><Markdown>{detail.environmentMarkdown}</Markdown></details></div></section>
      <EditorPane key={`${user?.id || 'guest'}:${id}:${context}:${target.processor}:${target.language}`} problem={problem.data} detail={detail} target={target} context={context} contest={contest.data} phase={phase} onCloseOutput={returnToStatement} contestLoading={context !== 'practice' && (contest.isPending || Boolean(contest.error))} />
    </div> : tab === 'submissions' ? <SubmissionList key={`${id}:${context}:${user?.id || 'guest'}`} problemId={id} context={context} /> : <Leaders problemId={id} processor={target.processor} context={context} finished={phase === 'finished'} />}
  </>}</QueryState>;
}
