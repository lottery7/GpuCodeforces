import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest as ContestRecord, ProblemDetail, Submission as SubmissionRecord, User } from '../src/api/types';
import Problem from '../src/pages/Problem';
import Problems from '../src/pages/Problems';
import Submission from '../src/pages/Submission';
import Contest from '../src/pages/Contest';
import ContestEditor from '../src/pages/ContestEditor';
import { SubmissionTable } from '../src/pages/Submissions';
import RuntimeNotice from '../src/components/RuntimeNotice';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({ default: ({ value, onChange, readOnly, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; readOnly?: boolean; label?: string }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));

const owner: User = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
const opponent: User = { ...owner, id: 'opponent', handle: 'opponent_cuda' };
const task: ProblemDetail = { id: 'vector', code: '100', title: 'Сложение', format: 'guess', difficulty: 'easy', tags: [], solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null, bestTimeMs: null, recordTimeMs: null, statementMarkdown: 'Какой код быстрее?', starterCode: '', timeLimitSeconds: 2, memoryLimitMb: 1024, environmentMarkdown: 'NVIDIA A100', firstCode: 'void first() {}', secondCode: 'void second() {}', guessSubmissionId: null, referenceMeasurement: { key: 'environment-v1', status: 'ready', measuredAt: null, diagnostic: null } };
const answer: SubmissionRecord = { id: '42', problemId: task.id, problemTitle: task.title, format: 'guess', user: owner, contestId: null, contestTitle: null, createdAt: '2026-01-01T10:00:00Z', status: 'accepted', timeMs: null, comparable: false, guessResult: { choice: 'first', correctChoice: 'first', firstTimeMs: 0.1, secondTimeMs: 0.2, explanation: 'Первый код читает память последовательно.' } };
const duel: ContestRecord = { id: 'duel', title: 'CUDA Duel', format: 'duel', challengerId: owner.id, opponentId: opponent.id, startAt: new Date(Date.now() - 60000).toISOString(), endAt: new Date(Date.now() + 3600000).toISOString(), authors: [owner], problemIds: [task.id], problemCount: 1, registered: false, gpu: 'NVIDIA A100' };

function open(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><RuntimeNotice /><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/problem/:id" element={<Problem />} /><Route path="/problems" element={<Problems />} />
    <Route path="/submission/:id" element={<Submission />} /><Route path="/contest/:id" element={<Contest />} />
    <Route path="/contests/new" element={<ContestEditor />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  session.user = owner;
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  vi.spyOn(api, 'problemLeaders').mockResolvedValue([]);
  vi.spyOn(api, 'getRuntime').mockResolvedValue({ executionMode: 'gpu', gpuName: 'NVIDIA A100' });
});

describe('Форматы задач', () => {
  it('принимает один ответ и показывает разбор без редактора и GPU-посылки', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(task);
    const submit = vi.spyOn(api, 'submitGuess').mockResolvedValue(answer);
    const coding = vi.spyOn(api, 'submitSolution');
    const example = vi.spyOn(api, 'checkExample');
    open('/problem/vector');
    expect(await screen.findByLabelText('Код 1')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('Код 2')).toHaveValue(task.secondCode);
    expect(screen.queryByRole('button', { name: 'Проверить пример' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Лидеры' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'Код 1 быстрее' }));
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(submit).toHaveBeenCalledWith(task.id, 'practice', { choice: 'first', benchmarkKey: task.referenceMeasurement!.key });
    expect(await screen.findByText('Верно')).toBeVisible();
    expect(screen.getByText(answer.guessResult!.explanation)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Проверить выбор' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Код 2 быстрее' })).toBeDisabled();
    expect(coding).not.toHaveBeenCalled(); expect(example).not.toHaveBeenCalled();
  });

  it('восстанавливает ответ текущей версии, но не блокирует новую версию старой посылкой', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, lastSubmissionId: 'old-version', guessSubmissionId: null });
    const previous = vi.spyOn(api, 'getSubmission').mockResolvedValue(answer);
    const client = open('/problem/vector');
    expect(await screen.findByRole('button', { name: 'Проверить выбор' })).toBeDisabled();
    await userEvent.click(screen.getByRole('radio', { name: 'Код 2 быстрее' }));
    expect(screen.getByRole('button', { name: 'Проверить выбор' })).toBeEnabled();
    expect(previous).not.toHaveBeenCalled();
    get.mockResolvedValue({ ...task, guessSubmissionId: answer.id });
    await client.invalidateQueries({ queryKey: ['problem'] });
    expect(await screen.findByText('Верно')).toBeVisible();
    expect(previous).toHaveBeenCalledWith(answer.id, expect.any(AbortSignal));
    expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).toBeChecked();
    expect(screen.queryByRole('button', { name: 'Проверить выбор' })).not.toBeInTheDocument();
    get.mockResolvedValue({ ...task, versionId: 'new-version', guessSubmissionId: null });
    await client.invalidateQueries({ queryKey: ['problem'] });
    expect(await screen.findByRole('button', { name: 'Проверить выбор' })).toBeVisible();
    expect(screen.queryByText('Верно')).not.toBeInTheDocument();
  });

  it('показывает ошибку ответа и позволяет повторить отправку', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(task);
    const submit = vi.spyOn(api, 'submitGuess').mockRejectedValueOnce(new Error('Связь потеряна.')).mockResolvedValue(answer);
    open('/problem/vector');
    await userEvent.click(await screen.findByRole('radio', { name: 'Код 1 быстрее' }));
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(await screen.findByText('Связь потеряна.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(await screen.findByText('Верно')).toBeVisible(); expect(submit).toHaveBeenCalledTimes(2);
  });

  it('восстанавливает данный код оптимизации и показывает исходный замер', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, format: 'optimization', starterCode: 'void solve() { slow(); }', baselineTimeMs: 1.25, bestTimeMs: 0.625 });
    open('/problem/vector');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    expect(editor).toHaveValue('void solve() { slow(); }');
    expect(screen.getByText('1.250')).toBeVisible();
    expect(screen.getByText('2.000x')).toBeVisible();
    fireEvent.change(editor, { target: { value: 'void solve() { fast(); }' } });
    await userEvent.click(screen.getByRole('button', { name: 'Восстановить исходный код' }));
    expect(editor).toHaveValue('void solve() { slow(); }');
  });

  it('сохраняет формат в URL и отправляет его серверу', async () => {
    const list = vi.spyOn(api, 'listProblems').mockResolvedValue({ items: [task], total: 1, tags: [] });
    open('/problems?format=guess');
    expect(await screen.findByRole('combobox', { name: 'Формат задачи' })).toHaveValue('guess');
    await waitFor(() => expect(list).toHaveBeenCalledWith(expect.objectContaining({ format: 'guess' }), expect.any(AbortSignal)));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Формат задачи' }), 'optimization');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ format: 'optimization' }), expect.any(AbortSignal)));
  });

  it.each([true, false])('раскрывает ответ только автору: %s', async own => {
    session.user = own ? owner : opponent;
    vi.spyOn(api, 'getSubmission').mockResolvedValue(answer);
    open('/submission/42');
    await screen.findByRole('heading', { name: 'Ответ #42' });
    expect(screen.queryByRole('button', { name: 'Открыть в редакторе' })).not.toBeInTheDocument();
    expect(screen.queryByText('Таблица лидеров')).not.toBeInTheDocument();
    expect(screen.queryByText(/Время GPU:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/другой конфигурации/)).not.toBeInTheDocument();
    expect(Boolean(screen.queryByText('Верно'))).toBe(own);
  });

  it('не помечает правильный ответ в истории старым профилем', () => {
    render(<MemoryRouter><SubmissionTable items={[answer]} /></MemoryRouter>);
    expect(screen.getByText('+')).toBeVisible();
    expect(screen.queryByText('Старый профиль проверки')).not.toBeInTheDocument();
  });
});

describe('Дуэль', () => {
  it('показывает живой результат соперника и не предлагает участие постороннему', async () => {
    session.user = { ...owner, id: 'spectator' };
    vi.spyOn(api, 'getContest').mockResolvedValue(duel);
    vi.spyOn(api, 'getProfile').mockResolvedValue({ user: opponent, solvedCount: 0, contests: [] });
    vi.spyOn(api, 'allProblems').mockResolvedValue([{ ...task, format: 'standard' }]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: opponent, solved: 1, totalSeconds: 0, times: {}, bestTimeMs: 0.052 }]);
    open('/contest/duel');
    const board = await screen.findByRole('region', { name: 'Результаты дуэли' });
    expect(await within(board).findByText('0.052')).toBeVisible();
    expect(within(board).queryByText(/Решена за/)).not.toBeInTheDocument();
    expect(within(board).getByText(opponent.handle)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Принять дуэль' })).not.toBeInTheDocument();
    expect(screen.getByText('Дуэль по приглашению')).toBeVisible();
  });

  it('показывает решенный guess и время сдачи без времени GPU', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue({ ...duel, format: 'standard', registered: true });
    vi.spyOn(api, 'allProblems').mockResolvedValue([{ ...task, solved: true, lastSubmissionId: answer.id, lastStatus: 'accepted' }]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: owner, solved: 1, totalSeconds: 125, times: { [task.id]: 125 } }]);
    open('/contest/duel');
    const table = await screen.findByRole('table', { name: 'Задачи соревнования' });
    expect(within(table).getByText('Принято')).toBeVisible();
    expect(within(table).queryByText('Не решена')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('link', { name: 'Таблица' }));
    const standings = await screen.findByRole('table', { name: 'Таблица контеста' });
    expect(within(standings).getAllByText('0:02:05')).toHaveLength(2);
    expect(within(standings).getByRole('columnheader', { name: 'A' })).toBeVisible();
  });

  it('показывает победителя по лучшему времени кода', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue({ ...duel, endAt: new Date(Date.now() - 1000).toISOString() });
    vi.spyOn(api, 'getProfile').mockResolvedValue({ user: opponent, solvedCount: 0, contests: [] });
    vi.spyOn(api, 'allProblems').mockResolvedValue([{ ...task, format: 'standard' }]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([
      { rank: 1, user: opponent, solved: 1, totalSeconds: 0, times: {}, bestTimeMs: 0.05 },
      { rank: 2, user: owner, solved: 1, totalSeconds: 0, times: {}, bestTimeMs: 1 },
    ]);
    open('/contest/duel');
    const board = await screen.findByRole('region', { name: 'Результаты дуэли' });
    expect(await within(board).findByText(/Победитель:/)).toHaveTextContent(opponent.handle);
    expect(await within(board).findByText('0.050')).toBeVisible();
  });

});

describe('Экран решения', () => {
  it('не добавляет лишние действия под редактором', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, format: 'standard' });
    open('/problem/vector');
    await screen.findByLabelText('Код решения на C++ и CUDA');
    expect(screen.queryByText('Сравнить мои решения')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'К условию' })).not.toBeInTheDocument();
  });

  it('сохраняет правки при просмотре исходного решения', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, format: 'optimization', starterCode: 'void solve() { original(); }' });
    open('/problem/vector');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    fireEvent.change(editor, { target: { value: 'void solve() { optimized(); }' } });
    await userEvent.click(screen.getByRole('tab', { name: 'Исходное решение' }));
    expect(screen.getByRole('textbox', { name: 'Исходное решение' })).toHaveValue('void solve() { original(); }');
    expect(screen.getByRole('textbox', { name: 'Исходное решение' })).toHaveAttribute('readonly');
    expect(screen.queryByRole('button', { name: 'Отправить' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Ваше решение' }));
    expect(screen.getByLabelText('Код решения на C++ и CUDA')).toHaveValue('void solve() { optimized(); }');
  });

  it('показывает условия сравнения без служебной таблицы окружения', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, environmentMarkdown: 'Mock GPU sm_86 nvcc', benchmark: { inputDescription: 'Три размера векторов', aggregation: 'geometric_mean', timingMode: 'wall', warmupRuns: 1, maxRuns: 3, gpuName: 'Mock GPU', synthetic: true } });
    open('/problem/vector');
    const measurements = await screen.findByText('Замеры: геометрическое среднее');
    expect(screen.getByText('Три размера векторов')).not.toBeVisible();
    await userEvent.click(measurements);
    expect(screen.getByText('Три размера векторов')).toBeVisible();
    expect(screen.queryByText(/Mock GPU/)).not.toBeInTheDocument();
    expect(screen.queryByText('Среда и замер')).not.toBeInTheDocument();
  });

  it.each(['/problem/vector', '/submission/42'])('помечает сохраненные тестовые результаты при запуске с GPU: %s', async path => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, benchmark: { inputDescription: 'Три размера векторов', aggregation: 'geometric_mean', timingMode: 'wall', warmupRuns: 1, maxRuns: 3, gpuName: null, synthetic: true } });
    vi.spyOn(api, 'getSubmission').mockResolvedValue({ ...answer, format: 'standard', timeMs: 0.1, comparable: true, measurement: { aggregation: 'max', timingMode: 'wall', gpuName: null, synthetic: true, testCount: 3 } });
    open(path);
    expect(await screen.findByText('Тестовые результаты')).toBeVisible();
    expect(screen.queryByText(/Локальный стенд:/)).not.toBeInTheDocument();
  });

  it('не дублирует общую пометку mock для тестовых результатов', async () => {
    vi.mocked(api.getRuntime).mockResolvedValue({ executionMode: 'mock', gpuName: null });
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, benchmark: { inputDescription: 'Три размера векторов', aggregation: 'geometric_mean', timingMode: 'wall', warmupRuns: 1, maxRuns: 3, gpuName: null, synthetic: true } });
    open('/problem/vector');
    expect(await screen.findByText(/Локальный стенд:/)).toBeVisible();
    await screen.findByText('Три размера векторов');
    expect(screen.queryByText('Тестовые результаты')).not.toBeInTheDocument();
  });

  it('показывает разбор неверного выбора на месте без повторной ссылки', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(task);
    vi.spyOn(api, 'submitGuess').mockResolvedValue({ ...answer, status: 'wrong_answer', guessResult: { ...answer.guessResult!, choice: 'equal' } });
    open('/problem/vector');
    await userEvent.click(await screen.findByRole('radio', { name: 'Одинаково' }));
    await userEvent.click(screen.getByRole('button', { name: 'Проверить выбор' }));
    expect(await screen.findByText('Не угадали')).toBeVisible();
    expect(screen.getByText('Вы выбрали: одинаково.')).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Посмотреть ответ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проверить выбор' })).not.toBeInTheDocument();
  });
});
