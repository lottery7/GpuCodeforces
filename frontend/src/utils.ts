import type { Contest } from './api/types';

export const pendingStatuses = new Set(['queued', 'compiling', 'checking', 'measuring']);
export const statusLabels: Record<string, string> = {
  queued: 'В очереди', compiling: 'Компиляция', checking: 'Проверка', measuring: 'Замер',
  accepted: 'Принято', compile_error: 'Ошибка компиляции', wrong_answer: 'Неверный ответ',
  runtime_error: 'Ошибка выполнения', time_limit: 'Превышен лимит', memory_limit: 'Превышена память',
  system_error: 'Ошибка системы', measurement_error: 'Замер недоступен',
};
export const difficultyLabels: Record<string, string> = { easy: 'Легкая', medium: 'Средняя', hard: 'Сложная' };
export const formatLabels = { standard: 'Решение с нуля', optimization: 'Оптимизация решения', guess: 'Угадай, какой код быстрее' };
export const guessLabels = { first: 'Код 1 лучше', second: 'Код 2 лучше', equal: 'Одинаково' };
export function canJoinContest(contest: Contest, userId: string | undefined) {
  return contest.format !== 'duel' || Boolean(userId && (contest.challengerId === userId || contest.opponentId === userId));
}
export const dateTime = (value: string) => new Intl.DateTimeFormat('ru-RU', { timeZone: 'Europe/Moscow', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
export const userPath = (id: string) => `/profile?user=${encodeURIComponent(id)}`;
export const shortId = (id: string) => id.length > 12 ? id.slice(0, 8) : id;
export function queryParams(values: Record<string, string | number | boolean | null | undefined>) {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') params.set(key, String(value)); });
  return params.toString();
}
/** [target] keeps the processor and language chosen on the problem page. */
export function problemPath(id: string, context = 'practice', tab?: string, target?: { processor: string; language: string }) {
  const query = queryParams({
    contest: context === 'practice' ? undefined : context,
    tab: tab && tab !== 'statement' ? tab : undefined,
    processor: target?.processor,
    language: target?.language,
  });
  return `/problem/${encodeURIComponent(id)}${query ? '?' + query : ''}`;
}
export const contextFromSearch = (params: URLSearchParams) => params.get('contest') || 'practice';
/** Contest solve time: seconds from the start to the first accepted submission, as h:mm:ss. */
export function solveTime(seconds: number) {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${Math.floor(seconds / 3600)}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}`;
}
export function phaseOf(contest: Pick<Contest, 'startAt' | 'endAt'>, now = Date.now()): 'upcoming' | 'live' | 'finished' {
  return now < Date.parse(contest.startAt) ? 'upcoming' : now < Date.parse(contest.endAt) ? 'live' : 'finished';
}
export function safeReturnTo(value: string | null) {
  return value?.startsWith('/') && !value.startsWith('//') && !/[\\\x00-\x1f\x7f]/.test(value) ? value : '/profile';
}
