import type { ProblemProcessorDetail } from '../api/types';
import { SyntheticResultsNotice } from './RuntimeNotice';
import { t } from '../i18n';

/** How results of a format are compared on the chosen processor; the GPU's timing only on the real GPU. */
export default function BenchmarkInfo({ benchmark }: { benchmark: ProblemProcessorDetail['benchmark'] }) {
  if (!benchmark) return null;
  const geometric = benchmark.aggregation === 'geometric_mean';
  const gpu = benchmark.timingMode != null;
  return <details className="benchmark-info" aria-label={t('Условия замера')}>
    <summary>{t('Замеры:')} {geometric ? t('геометрическое среднее') : t('максимум по тестам')} <SyntheticResultsNotice synthetic={benchmark.synthetic} /></summary>
    <p>{benchmark.inputDescription}</p>
    {benchmark.model && !benchmark.synthetic && <p>{gpu ? 'GPU' : t('Модель')}: {benchmark.model}</p>}
    <p>{geometric ? t('Итоговый результат - геометрическое среднее результатов тестов. Отношение результатов показывает среднее относительное улучшение на одном и том же наборе входов.') : t('Итоговый результат - результат самого тяжелого теста. Результаты сравниваются на одном и том же наборе входов.')}</p>
    {!gpu ? <p>{t('Результат теста считает эмулятор: он не зависит от нагрузки машины, и один прогон дает точное значение.')}</p> : <>
      <p>{benchmark.timingMode === 'wall' ? t('Измеряется весь вызов solve до завершения запущенной работы, включая запуск и синхронизацию GPU.') : t('Измеряются интервалы работы ядер GPU; перекрывающиеся интервалы учитываются один раз.')}</p>
      {(benchmark.warmupRuns ?? 0) > 0 && <p>{t('Перед первым тестом:')} {benchmark.warmupRuns} {t('прогревочных запусков.')}</p>}
      <p>{(benchmark.maxRuns ?? 1) > 1 ? t('Рядом с лимитом времени выполняется до {0} запусков; при повторах берется медиана. Остальные тесты измеряются за один запуск.', [benchmark.maxRuns]) : t('Каждый тест измеряется за один запуск.')}</p>
    </>}
  </details>;
}
