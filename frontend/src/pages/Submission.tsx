import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { api } from '../api/client';
import { useSession } from '../auth';
import CodeEditor from '../components/CodeEditor';
import GuessResult from '../components/GuessResult';
import { SyntheticResultsNotice } from '../components/RuntimeNotice';
import { writeDraft } from '../drafts';
import { GPU_CUDA, useProcessors } from '../processors';
import { ErrorMessage, Markdown, PageHeader, QueryState, ScoreValue, Status, UserLink } from '../ui';
import { dateTime, pendingStatuses, problemPath, shortId } from '../utils';
import { HackForm } from '../components/Hacks';
import { t, translated } from '../i18n';


/** Names of the further numbers the judge reports, by processor. */
const metricLabels: Record<string, string> = translated({
  cycles: 'Такты', depth: 'Глубина логики', transistors: 'Транзисторы', cells: 'Ячейки', instructions: 'Инструкции', l1_misses: 'Промахи L1', ll_misses: 'Промахи последнего уровня',
});

export default function Submission() {
  const { id = '' } = useParams();
  const { user, isLoading } = useSession();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const processors = useProcessors();
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
  const context = item?.context || item?.contestId || 'practice';
  const pending = Boolean(item && pendingStatuses.has(item.status));
  const accepted = item?.status === 'accepted';
  const isGuess = item?.format === 'guess';
  const retryAvailable = owner && item?.recheckAllowed !== false && (item?.status === 'system_error' || item?.status === 'measurement_error');
  function edit() {
    if (!item || typeof item.code !== 'string' || !user) return;
    const target = { processor: item.processor, language: item.language };
    writeDraft(user.id, { problemId: item.problemId, context, ...target }, item.code);
    navigate(problemPath(item.problemId, context, undefined, target));
  }
  let comparison: string | null = null;
  const score = item?.score;
  const previous = item?.previousBestScore;
  if (accepted && item?.comparable && score && previous && previous.type === score.type && previous.value > 0) {
    const change = (score.value / previous.value - 1) * 100;
    comparison = Math.abs(change) < 0.01 ? t('Повторяет личный рекорд.') : change < 0 ? t('Новый личный рекорд: лучше на {0}%.', [Math.abs(change).toFixed(1)]) : t('Хуже личного рекорда на {0}%.', [change.toFixed(1)]);
  }
  const gpu = item?.processor === GPU_CUDA;
  const language = item ? processors.language(item.processor, item.language)?.title ?? item.language : '';
  return <QueryState query={submission}>{item && <>
    <PageHeader title={`${isGuess ? t('Ответ') : t('Посылка')} #${shortId(item.id)}`} actions={!isGuess && owner && typeof item.code === 'string' ? <button type="button" className="btn secondary" onClick={edit}>{t('Открыть в редакторе')}</button> : undefined} />
    <p className="breadcrumbs"><Link to={problemPath(item.problemId, context, undefined, { processor: item.processor, language: item.language })}>{item.problemTitle}</Link><span>/</span><UserLink user={item.user} /><span>/</span>{item.contestId ? <Link to={`/contest/${encodeURIComponent(item.contestId)}`}>{item.contestTitle || t('Контест')}</Link> : t('Практика')}<span>/</span><span>{processors.title(item.processor)}, {language}</span><span>/</span><time dateTime={item.createdAt}>{dateTime(item.createdAt)} {t('МСК')}</time></p>
    <div className="submission-verdict-bar" aria-live="polite">
      <h2><Status status={item.status} />{item.totalTests != null && <span className="muted"> {item.passedTests ?? 0}/{item.totalTests}</span>}</h2>
      {accepted && !isGuess && <p className="metrics">{item.measurement?.aggregation === 'geometric_mean' ? t('Геометрическое среднее') : gpu ? t('Время GPU') : t('Результат')}: <strong><ScoreValue score={item.score} /></strong></p>}
    </div>
    <section className="submission-summary">
      {pending && <div className="submission-steps" aria-label={t('Этапы проверки')}>{[['queued', t('Очередь')], ['compiling', t('Компиляция')], ['checking', t('Корректность')], ['measuring', gpu ? t('Замер GPU') : t('Замер')]].map(([status, label]) => <span key={status} className={status === item.status ? 'current' : undefined}>{label}</span>)}</div>}
      {comparison && <p>{comparison} {t('Предыдущий:')} <ScoreValue score={item.previousBestScore} />.</p>}
      {accepted && !isGuess && item.metrics && Object.keys(item.metrics).length > 0 && <dl className="metric-list" aria-label={t('Показатели посылки')}>{Object.entries(item.metrics).map(([name, value]) => <div key={name}><dt>{metricLabels[name] ?? name}</dt><dd className="mono">{new Intl.NumberFormat('ru-RU').format(value)}</dd></div>)}</dl>}
      {accepted && !isGuess && !item.comparable && <p className="notice">{t('Результат получен в другой конфигурации проверки и не участвует в текущих рекордах.')}</p>}
      {(retryAvailable || Boolean(recheck.error)) && <div className="submission-actions">
        {retryAvailable && <button type="button" className="btn secondary" onClick={() => recheck.mutate()} disabled={recheck.isPending}>{recheck.isPending ? t('Возвращаем в очередь...') : t('Повторить проверку')}</button>}
        <ErrorMessage error={recheck.error} reveal />
      </div>}
      {owner && !pending && !accepted && item.diagnostic && <pre className="diagnostic" tabIndex={0} aria-label={t('Диагностика посылки')}>{item.diagnostic}</pre>}
    </section>
    {isGuess ? owner && item.guessResult ? <GuessResult result={item.guessResult} /> : <p className="muted small">{t('Выбранный ответ доступен автору.')}</p> : <>
      {accepted && <p>{context.startsWith('virtual:') ? <Link to={`/virtual/${encodeURIComponent(context.slice(8))}`}>{t('Виртуальное участие')}</Link> : item.contestId && !context.startsWith('upsolve:') ? <Link to={`/contest/${encodeURIComponent(item.contestId)}?tab=standings`}>{t('Результаты соревнования')}</Link> : <Link to={problemPath(item.problemId, context, 'leaders', { processor: item.processor, language: item.language })}>{t('Таблица лидеров')}</Link>}</p>}
      {(owner || item.sourcePublic) && typeof item.code === 'string' ? <details className="disclosure" open={!accepted && !pending}><summary>{t('Код решения')}</summary><div className="editor-panel"><CodeEditor value={item.code} readOnly language={item.language} label={t('Исходный код посылки')} /></div></details> : <p className="muted small">{t('Исходный код доступен автору посылки.')}</p>}

    </>}
    {item.hackable && item.contestId && <HackForm submissionId={item.id} contestId={item.contestId} />}
    {item.environmentMarkdown && !isGuess && <details className="disclosure"><summary>{t('Среда и методика замера')}</summary><Markdown>{item.environmentMarkdown}</Markdown></details>}
    {item.measurement && !isGuess && <p className="small muted">{item.measurement.timingMode == null ? t('Результат теста посчитал эмулятор.') : item.measurement.timingMode === 'wall' ? t('Измеряется вызов решения до завершения работы, включая запуск и синхронизацию GPU.') : t('Измеряются интервалы работы ядер GPU.')} {t('Замерных тестов:')} {item.measurement.testCount}.{item.measurement.model && !item.measurement.synthetic && t(' Модель: {0}.', [item.measurement.model])}{' '}<SyntheticResultsNotice synthetic={item.measurement.synthetic} /></p>}
  </>}</QueryState>;
}
