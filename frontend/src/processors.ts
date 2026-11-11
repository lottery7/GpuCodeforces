import { useQuery } from '@tanstack/react-query';
import { api } from './api/client';
import type { Processor, Score, ScoreType } from './api/types';
import { getLanguage, locale, translated } from './i18n';

/** The processor of packages written before processors existed and of the real GPU. */
export const GPU_CUDA = 'gpu-cuda';

/** Unit a score of a type is shown in. */
export const scoreUnits: Record<ScoreType, string> = translated({
  TIME_MS: 'мс',
  CYCLES: 'такты',
  CYCLES_X_DEPTH_X_TRANSISTORS: 'такты × глубина × транзисторы',
  WEIGHTED_INSTRUCTIONS: 'усл. такты',
  INSTRUCTIONS: 'инструкции',
});

/** What the limit of a score type is called. */
export const limitLabels: Record<ScoreType, string> = translated({
  TIME_MS: 'Лимит времени',
  CYCLES: 'Лимит тактов',
  CYCLES_X_DEPTH_X_TRANSISTORS: 'Лимит тактов × глубины × транзисторов',
  WEIGHTED_INSTRUCTIONS: 'Лимит условных тактов',
  INSTRUCTIONS: 'Лимит инструкций',
});

/** Times keep up to four decimals; counts of cycles are grouped by thousands. */
export function formatScore(score: Score) {
  if (!Number.isFinite(score.value)) return '-';
  if (score.type === 'TIME_MS') return String(Number(score.value.toFixed(4)));
  return new Intl.NumberFormat(locale(), { maximumFractionDigits: 2 }).format(score.value);
}

const SUPERSCRIPT_DIGITS = '⁰¹²³⁴⁵⁶⁷⁸⁹';

/**
 * A limit as the problem page shows it. Authors pick round limits, and from a million on such a limit reads better as
 * a power of ten: `3,5·10⁹`, `10¹³`. Any other is shown as a score (the backend's environment block does the same).
 */
export function formatLimit(limit: Score) {
  const { value } = limit;
  if (!Number.isSafeInteger(value) || value < 1e6) return formatScore(limit);
  const digits = String(value);
  const significant = digits.replace(/0+$/, '');
  if (significant.length > 3) return formatScore(limit);
  const power = '10' + [...String(digits.length - 1)].map(digit => SUPERSCRIPT_DIGITS[Number(digit)]).join('');
  // the decimal mark of the language: a comma in Russian, a point in English
  const mantissa = significant.length > 1 ? `${significant[0]}${getLanguage() === 'ru' ? ',' : '.'}${significant.slice(1)}` : significant;
  return mantissa === '1' ? power : `${mantissa}·${power}`;
}

/**
 * The platform's processors, loaded once and kept for the whole session. Pages work without them: they fall back
 * to processor ids, and units come from the scores themselves.
 */
export function useProcessors() {
  const query = useQuery({
    queryKey: ['processors'],
    queryFn: ({ signal }) => api.listProcessors(signal),
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  });
  const processors: Processor[] = query.data ?? [];
  const find = (id: string) => processors.find(processor => processor.id === id);
  return {
    processors,
    isPending: query.isPending,
    error: query.error,
    refetch: query.refetch,
    find,
    title: (id: string) => find(id)?.title ?? id,
    language: (processor: string, language: string) => find(processor)?.languages.find(item => item.id === language),
  };
}
