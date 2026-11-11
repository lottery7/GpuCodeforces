import type { ProblemProcessor } from '../api/types';
import { t } from '../i18n';

/** Progress or failure of the reference measurements of a format on one processor; nothing once they are ready. */
export default function ReferenceStatus({ format, measurement }: { format?: string; measurement: ProblemProcessor['referenceMeasurement'] }) {
  if (measurement?.status === 'ready' && (format !== 'guess' || measurement.key)) return null;
  const failed = measurement?.status === 'failed';
  return <section className={`reference-status${failed ? ' failed' : ''}`} aria-label={t('Эталонный замер')} role="status">
    <strong>{failed ? format === 'optimization' ? t('Не удалось измерить исходное решение') : t('Не удалось измерить варианты кода') : format === 'optimization' ? t('Исходное решение измеряется') : t('Варианты кода измеряются')}</strong>
    {failed && measurement.diagnostic && <p>{measurement.diagnostic}</p>}
  </section>;
}
