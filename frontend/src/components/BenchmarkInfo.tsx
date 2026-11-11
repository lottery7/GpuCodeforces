import type { ProblemProcessorDetail } from '../api/types';
import { SyntheticResultsNotice } from './RuntimeNotice';

/** How results of a format are compared on the chosen processor; the GPU's timing only on the real GPU. */
export default function BenchmarkInfo({ benchmark }: { benchmark: ProblemProcessorDetail['benchmark'] }) {
  if (!benchmark) return null;
  const geometric = benchmark.aggregation === 'geometric_mean';
  const gpu = benchmark.timingMode != null;
  return <section className="benchmark-info" aria-label="Условия сравнения">
    <h2>Условия сравнения</h2>
    <p>{benchmark.inputDescription}</p>
    <div className="benchmark-facts"><span>{geometric ? 'Геометрическое среднее по тестам' : 'Максимум по тестам'}</span>{benchmark.model && !benchmark.synthetic && <span>{gpu ? 'Целевая GPU' : 'Модель'}: {benchmark.model}</span>}<SyntheticResultsNotice synthetic={benchmark.synthetic} /></div>
    <details><summary>Как считается результат</summary>
      <p>{geometric ? 'Итоговый результат - геометрическое среднее результатов замерных тестов. Отношение результатов показывает среднее относительное улучшение на одном и том же наборе входов.' : 'Итоговый результат - результат самого тяжелого замерного теста. Результаты сравниваются на одном и том же наборе входов.'}</p>
      {!gpu ? <p>Результат теста считает эмулятор: он не зависит от нагрузки машины, и один прогон дает точное значение.</p> : <>
        <p>{benchmark.timingMode === 'wall' ? 'Измеряется весь вызов solve до завершения запущенной работы, включая запуск и синхронизацию GPU.' : 'Измеряются интервалы работы ядер GPU; перекрывающиеся интервалы учитываются один раз.'}</p>
        {(benchmark.warmupRuns ?? 0) > 0 && <p>Перед первым тестом: {benchmark.warmupRuns} прогревочных запусков.</p>}
        <p>{(benchmark.maxRuns ?? 1) > 1 ? `Рядом с лимитом времени выполняется до ${benchmark.maxRuns} запусков; при повторах берется медиана. Остальные тесты измеряются за один запуск.` : 'Каждый тест измеряется за один запуск.'}</p>
      </>}
    </details>
  </section>;
}
