import type { ProblemDetail } from '../api/types';
import { SyntheticResultsNotice } from './RuntimeNotice';

export default function BenchmarkInfo({ benchmark }: { benchmark: ProblemDetail['benchmark'] }) {
  if (!benchmark) return null;
  const geometric = benchmark.aggregation === 'geometric_mean';
  return <section className="benchmark-info" aria-label="Условия сравнения">
    <h2>Условия сравнения</h2>
    <p>{benchmark.inputDescription}</p>
    <div className="benchmark-facts"><span>{geometric ? 'Геометрическое среднее по тестам' : 'Максимум по тестам'}</span>{benchmark.gpuName && !benchmark.synthetic && <span>Целевая GPU: {benchmark.gpuName}</span>}<SyntheticResultsNotice synthetic={benchmark.synthetic} /></div>
    <details><summary>Как считается время</summary>
      <p>{geometric ? 'Итоговое время - геометрическое среднее времен тестов. Отношение результатов показывает среднее относительное ускорение на одном и том же наборе входов.' : 'Итоговое время - время самого медленного теста. Результаты сравниваются на одном и том же наборе входов.'}</p>
      <p>{benchmark.timingMode === 'wall' ? 'Измеряется весь вызов solve до завершения запущенной работы, включая запуск и синхронизацию GPU.' : 'Измеряются интервалы работы ядер GPU; перекрывающиеся интервалы учитываются один раз.'}</p>
      {benchmark.warmupRuns > 0 && <p>Перед первым тестом: {benchmark.warmupRuns} прогревочных запусков.</p>}
      <p>{benchmark.maxRuns > 1 ? `Рядом с лимитом времени выполняется до ${benchmark.maxRuns} запусков; при повторах берется медиана. Остальные тесты измеряются за один запуск.` : 'Каждый тест измеряется за один запуск.'}</p>
    </details>
  </section>;
}
