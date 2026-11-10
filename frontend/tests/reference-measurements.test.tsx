import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import type { ProblemDetail, Submission as SubmissionRecord, User } from '../src/api/types';
import Problem from '../src/pages/Problem';
import Submission from '../src/pages/Submission';

const owner: User = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
vi.mock('../src/auth', () => ({ useSession: () => ({ user: { id: 'owner', handle: 'cuda_user' }, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({ default: ({ value, onChange, readOnly, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; readOnly?: boolean; label?: string }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));
const ready: NonNullable<ProblemDetail['referenceMeasurement']> = { key: 'environment-a', status: 'ready', measuredAt: '2026-11-11T10:00:00Z', diagnostic: null };
const problem: ProblemDetail = { id: 'vector', versionId: 'version', code: '100', title: 'Сложение', format: 'guess', difficulty: 'easy', tags: [], solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null, bestTimeMs: null, recordTimeMs: null, statementMarkdown: 'Сравните два кода.', starterCode: 'void solve() { slow(); }', timeLimitSeconds: 2, memoryLimitMb: 1024, environmentMarkdown: '', firstCode: 'void first() {}', secondCode: 'void second() {}', guessSubmissionId: null, referenceMeasurement: ready };
const answer: SubmissionRecord = { id: '42', problemId: problem.id, problemTitle: problem.title, format: 'guess', user: owner, contestId: null, contestTitle: null, createdAt: ready.measuredAt!, status: 'accepted', timeMs: null, comparable: false, guessResult: { choice: 'first', correctChoice: 'first', firstTimeMs: 0.123, secondTimeMs: 0.456, explanation: 'Первый вариант быстрее.', benchmarkKey: ready.key!, synthetic: true } };

function open(path = '/problem/vector') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/problem/:id" element={<Problem />} /><Route path="/submission/:id" element={<Submission />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

describe('Эталонные измерения', () => {
  it('обновляет выполняющийся замер и останавливает опрос при готовности, сохраняя правки', async () => {
    vi.useFakeTimers();
    try {
      const get = vi.spyOn(api, 'getProblem')
        .mockResolvedValueOnce({ ...problem, format: 'optimization', baselineTimeMs: 999, bestTimeMs: 444, referenceMeasurement: { ...ready, status: 'running' } })
        .mockResolvedValue({ ...problem, format: 'optimization', baselineTimeMs: 2, bestTimeMs: 1 });
      open();
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByText('Исходное решение измеряется')).toBeVisible();
      expect(screen.queryByText('999.000')).not.toBeInTheDocument();
      expect(screen.queryByText('444.000')).not.toBeInTheDocument();
      expect(screen.queryByText('Ускорение')).not.toBeInTheDocument();
      const editor = screen.getByLabelText('Код решения на C++ и CUDA');
      fireEvent.change(editor, { target: { value: 'void solve() { optimized(); }' } });
      expect(screen.getByRole('button', { name: 'Отправить' })).toBeEnabled();
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(screen.getByText('2.000x')).toBeVisible();
      expect(editor).toHaveValue('void solve() { optimized(); }');
      expect(screen.queryByText('Исходное решение измеряется')).not.toBeInTheDocument();
      expect(get).toHaveBeenCalledTimes(2);
      await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
      expect(get).toHaveBeenCalledTimes(2);
    } finally { vi.useRealTimers(); }
  });

  it('после ошибки не показывает старые времена, но разрешает отправить код оптимизации', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...problem, format: 'optimization', baselineTimeMs: 999, bestTimeMs: 444, referenceMeasurement: { ...ready, status: 'failed', diagnostic: 'Исполнитель временно недоступен.' } });
    const submit = vi.spyOn(api, 'submitSolution').mockResolvedValue({ ...answer, format: 'optimization', status: 'queued' });
    vi.spyOn(api, 'getSubmission').mockResolvedValue({ ...answer, format: 'optimization', status: 'accepted', timeMs: 1 });
    open();
    expect(await screen.findByText('Не удалось измерить исходное решение')).toBeVisible();
    expect(screen.getByText('Исполнитель временно недоступен.')).toBeVisible();
    expect(screen.queryByText('999.000')).not.toBeInTheDocument();
    expect(screen.queryByText('444.000')).not.toBeInTheDocument();
    expect(screen.queryByText('Ускорение')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));
    expect(submit).toHaveBeenCalledWith({ problemId: problem.id, code: problem.starterCode });
  });

  it('держит коды открытыми и блокирует угадывание до готовности замеров', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue({ ...problem, guessSubmissionId: answer.id, referenceMeasurement: { ...ready, status: 'pending' } });
    const previous = vi.spyOn(api, 'getSubmission').mockResolvedValue(answer);
    const submit = vi.spyOn(api, 'submitGuess').mockResolvedValue(answer);
    const cache = open();
    expect(await screen.findByText('Варианты кода измеряются')).toBeVisible();
    expect(screen.getByLabelText('Код 1')).toHaveValue(problem.firstCode);
    expect(screen.getByLabelText('Код 2')).toHaveValue(problem.secondCode);
    expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
    expect(previous).not.toHaveBeenCalled();
    expect(screen.queryByText('Верно')).not.toBeInTheDocument();
    get.mockResolvedValue(problem);
    await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); });
    await userEvent.click(screen.getByRole('radio', { name: 'Код 1 быстрее' }));
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(await screen.findByText('Верно')).toBeVisible();
    expect(submit).toHaveBeenCalledWith(problem.id, 'practice', { choice: 'first', benchmarkKey: ready.key });
  });

  it('показывает ошибку замера угадывания без опроса и позволяет ответить после обновления', async () => {
    vi.useFakeTimers();
    try {
      const get = vi.spyOn(api, 'getProblem').mockResolvedValue({ ...problem, referenceMeasurement: { ...ready, status: 'failed', diagnostic: 'Замер не завершен.' } });
      const cache = open();
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByText('Не удалось измерить варианты кода')).toBeVisible();
      expect(screen.getByText('Замер не завершен.')).toBeVisible();
      expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
      await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
      expect(get).toHaveBeenCalledTimes(1);
      get.mockResolvedValue(problem);
      await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).toBeEnabled();
      expect(screen.queryByText('Замер не завершен.')).not.toBeInTheDocument();
    } finally { vi.useRealTimers(); }
  });

  it('при смене ключа сбрасывает выбранный вариант и старый разбор без смены версии задачи', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    vi.spyOn(api, 'getSubmission').mockResolvedValue({ ...answer, id: '43', guessResult: { ...answer.guessResult!, firstTimeMs: 0.321, secondTimeMs: 0.654, benchmarkKey: 'environment-b' } });
    vi.spyOn(api, 'getRuntime').mockResolvedValue({ executionMode: 'gpu', gpuName: 'NVIDIA A100' });
    const cache = open();
    await userEvent.click(await screen.findByRole('radio', { name: 'Код 2 быстрее' }));
    get.mockResolvedValue({ ...problem, referenceMeasurement: { ...ready, key: 'environment-b' } });
    await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Код 2 быстрее' })).not.toBeChecked());
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
    get.mockResolvedValue({ ...problem, guessSubmissionId: '43', referenceMeasurement: { ...ready, key: 'environment-b' } });
    await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); });
    expect(await screen.findByText('Верно')).toBeVisible();
    expect(screen.getByText('0.321')).toBeVisible();
    get.mockResolvedValue({ ...problem, referenceMeasurement: { ...ready, key: 'environment-c' } });
    await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); });
    await waitFor(() => expect(screen.queryByText('Верно')).not.toBeInTheDocument());
    expect(screen.queryByText('0.321')).not.toBeInTheDocument();
    expect(screen.queryByText('0.654')).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
  });

  it('не переносит завершившийся старый запрос ответа в новое окружение', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    let finish!: (value: SubmissionRecord) => void;
    vi.spyOn(api, 'submitGuess').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const cache = open();
    await userEvent.click(await screen.findByRole('radio', { name: 'Код 1 быстрее' }));
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    get.mockResolvedValue({ ...problem, referenceMeasurement: { ...ready, key: 'environment-b', status: 'running' } });
    await act(async () => { await cache.invalidateQueries({ queryKey: ['problem'] }); });
    await act(async () => finish(answer));
    await waitFor(() => expect(screen.queryByText('Верно')).not.toBeInTheDocument());
    expect(screen.getByText('Варианты кода измеряются')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
  });

  it('сохраняет старые времена и пометку mock в истории после перехода на GPU', async () => {
    vi.spyOn(api, 'getSubmission').mockResolvedValue(answer);
    vi.spyOn(api, 'getRuntime').mockResolvedValue({ executionMode: 'gpu', gpuName: 'NVIDIA A100' });
    open('/submission/42');
    expect(await screen.findByText('0.123')).toBeVisible();
    expect(screen.getByText('0.456')).toBeVisible();
    expect(await screen.findByText('Тестовые результаты')).toBeVisible();
  });

  it('после отказа из-за устаревшего ключа загружает новые условия и сбрасывает выбор', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    const submit = vi.spyOn(api, 'submitGuess').mockRejectedValue(new ApiError(400, 'Условия сравнения изменились. Обновите задачу.'));
    open();
    await userEvent.click(await screen.findByRole('radio', { name: 'Код 1 быстрее' }));
    get.mockResolvedValue({ ...problem, referenceMeasurement: { ...ready, key: 'environment-b' } });
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(submit).toHaveBeenCalledWith(problem.id, 'practice', { choice: 'first', benchmarkKey: ready.key });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).not.toBeChecked());
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
    expect(screen.queryByText('Верно')).not.toBeInTheDocument();
    expect(get).toHaveBeenCalledTimes(2);
  });
});
