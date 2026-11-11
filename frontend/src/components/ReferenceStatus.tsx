import type { ProblemProcessor } from '../api/types';

/** Progress or failure of the reference measurements of a format on one processor; nothing once they are ready. */
export default function ReferenceStatus({ format, measurement }: { format?: string; measurement: ProblemProcessor['referenceMeasurement'] }) {
  if (measurement?.status === 'ready' && (format !== 'guess' || measurement.key)) return null;
  const failed = measurement?.status === 'failed';
  return <section className={`reference-status${failed ? ' failed' : ''}`} aria-label="Эталонный замер" role="status">
    <strong>{failed ? format === 'optimization' ? 'Не удалось измерить исходное решение' : 'Не удалось измерить варианты кода' : format === 'optimization' ? 'Исходное решение измеряется' : 'Варианты кода измеряются'}</strong>
    {failed && measurement.diagnostic && <p>{measurement.diagnostic}</p>}
    {format === 'guess' && <p>Ответить можно после готовности измерений.</p>}
  </section>;
}
