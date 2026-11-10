import type { ProblemDetail } from '../api/types';

export default function ReferenceStatus({ problem }: { problem: ProblemDetail }) {
  const measurement = problem.referenceMeasurement;
  if (measurement?.status === 'ready' && (problem.format !== 'guess' || measurement.key)) return null;
  const failed = measurement?.status === 'failed';
  return <section className={`reference-status${failed ? ' failed' : ''}`} aria-label="Эталонный замер" role="status">
    <strong>{failed ? problem.format === 'optimization' ? 'Не удалось измерить исходное решение' : 'Не удалось измерить варианты кода' : problem.format === 'optimization' ? 'Исходное решение измеряется' : 'Варианты кода измеряются'}</strong>
    {failed && measurement.diagnostic && <p>{measurement.diagnostic}</p>}
    {problem.format === 'guess' && <p>Ответить можно после готовности измерений.</p>}
  </section>;
}
