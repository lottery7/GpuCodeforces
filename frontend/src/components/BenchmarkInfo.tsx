import type { ProblemDetail } from '../api/types';
import { SyntheticResultsNotice } from './RuntimeNotice';

export default function BenchmarkInfo({ benchmark }: { benchmark: ProblemDetail['benchmark'] }) {
  if (!benchmark) return null;
  const geometric = benchmark.aggregation === 'geometric_mean';
  return <details className="benchmark-info" aria-label="Условия замера">
    <summary>Замеры: {geometric ? 'геометрическое среднее' : 'максимум по тестам'} <SyntheticResultsNotice synthetic={benchmark.synthetic} /></summary>
    <p>{benchmark.inputDescription}</p>
    {benchmark.gpuName && !benchmark.synthetic && <p>GPU: {benchmark.gpuName}</p>}
      <p>{geometric ? 'Итоговое время - геометрическое среднее времен тестов. Отношение результатов показывает среднее относительное ускорение на одном и том же наборе входов.' : 'Итоговое время - время самого медленного теста. Результаты сравниваются на одном и том же наборе входов.'}</p>
      <p>{benchmark.timingMode === 'wall' ? 'Измеряется весь вызов solve до завершения запущенной работы, включая запуск и синхронизацию GPU.' : 'Измеряются интервалы работы ядер GPU; перекрывающиеся интервалы учитываются один раз.'}</p>
      {benchmark.warmupRuns > 0 && <p>Перед первым тестом: {benchmark.warmupRuns} прогревочных запусков.</p>}
      <p>{benchmark.maxRuns > 1 ? `Рядом с лимитом времени выполняется до ${benchmark.maxRuns} запусков; при повторах берется медиана. Остальные тесты измеряются за один запуск.` : 'Каждый тест измеряется за один запуск.'}</p>
  </details>;
}
