import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import CodeEditor from '../components/CodeEditor';
import { writeDraft } from '../drafts';
import { ErrorMessage, Markdown, PageHeader, QueryState, Status, TimeValue, UserLink } from '../ui';
import { dateTime, pendingStatuses, problemPath } from '../utils';

export default function Submission() {
  const { id = '' } = useParams();
  const { user, isLoading } = useSession();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const previousStatus = useRef<string | null>(null);
  const key = ['submission', id, user?.id];
  const submission = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => api.getSubmission(id, signal),
    enabled: Boolean(id) && !isLoading,
    refetchInterval: q => pendingStatuses.has(q.state.data?.status || '') ? 1500 : false,
  });
  const item = submission.data;
  useEffect(() => { previousStatus.current = null; }, [id, user?.id]);
  useEffect(() => {
    if (!item) return;
    if (previousStatus.current && pendingStatuses.has(previousStatus.current) && !pendingStatuses.has(item.status)) {
      void queryClient.invalidateQueries({ predicate: q => q.queryKey[0] !== 'submission' });
    }
    previousStatus.current = item.status;
  }, [item, id, user?.id, queryClient]);
  const recheck = useMutation({
    mutationFn: () => api.recheckSubmission(id),
    onSuccess: record => {
      queryClient.setQueryData(key, record);
      void queryClient.invalidateQueries({ predicate: q => q.queryKey[0] !== 'submission' });
    },
  });
  const owner = Boolean(user && item?.user.id === user.id);
  const context = item?.contestId || 'practice';
  const pending = Boolean(item && pendingStatuses.has(item.status));
  const accepted = item?.status === 'accepted';
  const retryAvailable = owner && (item?.status === 'system_error' || item?.status === 'measurement_error');
  function edit() {
    if (!item || typeof item.code !== 'string' || !user) return;
    writeDraft(user.id, item.problemId, context, item.code);
    navigate(problemPath(item.problemId, context));
  }
  let comparison: string | null = null;
  if (accepted && item?.comparable && item.timeMs !== null && item.previousBestTimeMs != null && item.previousBestTimeMs > 0) {
    const change = (item.timeMs / item.previousBestTimeMs - 1) * 100;
    comparison = Math.abs(change) < 0.01 ? 'Повторяет личный рекорд.' : change < 0 ? `Новый личный рекорд: быстрее на ${Math.abs(change).toFixed(1)}%.` : `Медленнее личного рекорда на ${change.toFixed(1)}%.`;
  }
  return <QueryState query={submission}>{item && <>
    <PageHeader title={`Посылка #${item.id}`} actions={owner && typeof item.code === 'string' ? <button type="button" className="btn secondary" onClick={edit}>Открыть в редакторе</button> : undefined} />
    <p className="breadcrumbs"><Link to={problemPath(item.problemId, context)}>{item.problemTitle}</Link><span>/</span><UserLink user={item.user} /><span>/</span>{item.contestId ? <Link to={`/contest/${encodeURIComponent(item.contestId)}`}>{item.contestTitle || 'Контест'}</Link> : 'Практика'}<span>/</span><time dateTime={item.createdAt}>{dateTime(item.createdAt)} МСК</time></p>
    <div className="submission-verdict-bar" aria-live="polite">
      <h2><Status status={item.status} />{item.totalTests != null && <span className="muted"> {item.passedTests ?? 0}/{item.totalTests}</span>}</h2>
      {accepted && <p className="metrics">Время GPU: <strong className="mono"><TimeValue value={item.timeMs} /> мс</strong></p>}
    </div>
    <section className="submission-summary">
      {pending && <div className="submission-steps" aria-label="Этапы проверки">{[['queued', 'Очередь'], ['compiling', 'Компиляция'], ['checking', 'Корректность'], ['measuring', 'Замер GPU']].map(([status, label]) => <span key={status} className={status === item.status ? 'current' : undefined}>{label}</span>)}</div>}
      {comparison && <p>{comparison} Предыдущий: <TimeValue value={item.previousBestTimeMs} /> мс.</p>}
      {accepted && !item.comparable && <p className="notice">Результат получен в другой конфигурации проверки и не участвует в текущих рекордах.</p>}
      {(retryAvailable || Boolean(recheck.error)) && <div className="submission-actions">
        {retryAvailable && <button type="button" className="btn secondary" onClick={() => recheck.mutate()} disabled={recheck.isPending}>{recheck.isPending ? 'Возвращаем в очередь...' : 'Повторить проверку'}</button>}
        <ErrorMessage error={recheck.error} reveal />
      </div>}
      {owner && !pending && !accepted && item.diagnostic && <pre className="diagnostic" tabIndex={0} aria-label="Диагностика посылки">{item.diagnostic}</pre>}
    </section>
    {accepted && <p><Link to={problemPath(item.problemId, context, 'leaders')}>Таблица лидеров</Link></p>}
    {owner && typeof item.code === 'string' ? <details className="disclosure" open={!accepted && !pending}><summary>Код решения</summary><div className="editor-panel"><CodeEditor value={item.code} readOnly label="Исходный код посылки" /></div></details> : <p className="muted small">Исходный код доступен автору посылки.</p>}
    {item.environmentMarkdown && <details className="disclosure"><summary>Среда и методика замера</summary><Markdown>{item.environmentMarkdown}</Markdown></details>}
  </>}</QueryState>;
}
