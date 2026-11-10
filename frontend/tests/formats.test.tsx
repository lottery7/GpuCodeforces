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

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({ default: ({ value, onChange, readOnly, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; readOnly?: boolean; label?: string }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));

const owner: User = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
const opponent: User = { ...owner, id: 'opponent', handle: 'opponent_cuda' };
const task: ProblemDetail = { id: 'vector', code: '100', title: 'Сложение', format: 'guess', difficulty: 'easy', tags: [], solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null, bestTimeMs: null, recordTimeMs: null, statementMarkdown: 'Какой код быстрее?', starterCode: '', timeLimitSeconds: 2, memoryLimitMb: 1024, environmentMarkdown: 'NVIDIA A100', firstCode: 'void first() {}', secondCode: 'void second() {}', guessSubmissionId: null };
const answer: SubmissionRecord = { id: '42', problemId: task.id, problemTitle: task.title, format: 'guess', user: owner, contestId: null, contestTitle: null, createdAt: '2026-01-01T10:00:00Z', status: 'accepted', timeMs: null, comparable: false, guessResult: { choice: 'first', correctChoice: 'first', firstTimeMs: 0.1, secondTimeMs: 0.2, explanation: 'Первый код читает память последовательно.' } };
const duel: ContestRecord = { id: 'duel', title: 'CUDA Duel', format: 'duel', challengerId: owner.id, opponentId: opponent.id, startAt: new Date(Date.now() - 60000).toISOString(), endAt: new Date(Date.now() + 3600000).toISOString(), authors: [owner], problemIds: [task.id], problemCount: 1, registered: false, gpu: 'NVIDIA A100' };

function open(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
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
    await userEvent.click(screen.getByRole('button', { name: 'Ответить' }));
    expect(submit).toHaveBeenCalledWith(task.id, 'practice', { choice: 'first' });
    expect(await screen.findByText('Верно!')).toBeVisible();
    expect(screen.getByText(answer.guessResult!.explanation)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Ответить' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Код 2 быстрее' })).toBeDisabled();
    expect(coding).not.toHaveBeenCalled(); expect(example).not.toHaveBeenCalled();
  });

  it('восстанавливает ответ текущей версии, но не блокирует новую версию старой посылкой', async () => {
    const get = vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, lastSubmissionId: 'old-version', guessSubmissionId: null });
    const previous = vi.spyOn(api, 'getSubmission').mockResolvedValue(answer);
    const client = open('/problem/vector');
    expect(await screen.findByRole('button', { name: 'Ответить' })).toBeDisabled();
    await userEvent.click(screen.getByRole('radio', { name: 'Код 2 быстрее' }));
    expect(screen.getByRole('button', { name: 'Ответить' })).toBeEnabled();
    expect(previous).not.toHaveBeenCalled();
    get.mockResolvedValue({ ...task, guessSubmissionId: answer.id });
    await client.invalidateQueries({ queryKey: ['problem'] });
    expect(await screen.findByText('Верно!')).toBeVisible();
    expect(previous).toHaveBeenCalledWith(answer.id, expect.any(AbortSignal));
    expect(screen.getByRole('radio', { name: 'Код 1 быстрее' })).toBeChecked();
    expect(screen.queryByRole('button', { name: 'Ответить' })).not.toBeInTheDocument();
    get.mockResolvedValue({ ...task, versionId: 'new-version', guessSubmissionId: null });
    await client.invalidateQueries({ queryKey: ['problem'] });
    expect(await screen.findByRole('button', { name: 'Ответить' })).toBeVisible();
    expect(screen.queryByText('Верно!')).not.toBeInTheDocument();
  });

  it('показывает ошибку ответа и позволяет повторить отправку', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(task);
    const submit = vi.spyOn(api, 'submitGuess').mockRejectedValueOnce(new Error('Связь потеряна.')).mockResolvedValue(answer);
    open('/problem/vector');
    await userEvent.click(await screen.findByRole('radio', { name: 'Код 1 быстрее' }));
    await userEvent.click(screen.getByRole('button', { name: 'Ответить' }));
    expect(await screen.findByText('Связь потеряна.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Ответить' }));
    expect(await screen.findByText('Верно!')).toBeVisible(); expect(submit).toHaveBeenCalledTimes(2);
  });

  it('восстанавливает данный код оптимизации и показывает исходный замер', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, format: 'optimization', starterCode: 'void solve() { slow(); }', baselineTimeMs: 1.25, bestTimeMs: 0.625 });
    open('/problem/vector');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    expect(editor).toHaveValue('void solve() { slow(); }');
    expect(screen.getByText('1.250')).toBeVisible();
    expect(screen.getByText(/Ускорение: 2.000x./)).toBeVisible();
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
    expect(Boolean(screen.queryByText('Верно!'))).toBe(own);
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
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: opponent, solved: 1, totalSeconds: 1200, times: { [task.id]: 1200 } }]);
    vi.spyOn(api, 'problemLeaders').mockResolvedValue([{ rank: 1, user: opponent, timeMs: 0.052, submissionId: 'fast' }]);
    open('/contest/duel');
    const board = await screen.findByRole('region', { name: 'Результаты дуэли' });
    expect(await within(board).findByText('0.052')).toBeVisible();
    expect(within(board).getByText('Решена за 0:20:00')).toBeVisible();
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

  it('определяет победителя по времени сдачи независимо от скорости GPU', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue({ ...duel, endAt: new Date(Date.now() - 1000).toISOString() });
    vi.spyOn(api, 'getProfile').mockResolvedValue({ user: opponent, solvedCount: 0, contests: [] });
    vi.spyOn(api, 'allProblems').mockResolvedValue([{ ...task, format: 'standard' }]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([
      { rank: 1, user: owner, solved: 1, totalSeconds: 600, times: { [task.id]: 600 } },
      { rank: 2, user: opponent, solved: 1, totalSeconds: 1200, times: { [task.id]: 1200 } },
    ]);
    vi.spyOn(api, 'problemLeaders').mockResolvedValue([
      { rank: 1, user: opponent, timeMs: 0.05, submissionId: 'fast' },
      { rank: 2, user: owner, timeMs: 1, submissionId: 'slow' },
    ]);
    open('/contest/duel');
    const board = await screen.findByRole('region', { name: 'Результаты дуэли' });
    expect(await within(board).findByText(/Победитель:/)).toHaveTextContent(owner.handle);
    expect(await within(board).findByText('0.050')).toBeVisible();
  });

  it('создает дуэль с одним соперником и одной общей задачей', async () => {
    vi.spyOn(api, 'allProblems').mockResolvedValue([{ ...task, format: 'standard' }, { ...task, id: 'second', code: '200', title: 'Другая', format: 'standard' }]);
    vi.spyOn(api, 'listUsers').mockResolvedValue({ items: [opponent], total: 1 });
    vi.spyOn(api, 'getProfile').mockResolvedValue({ user: opponent, solvedCount: 0, contests: [] });
    vi.spyOn(api, 'getContest').mockResolvedValue(duel);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([]);
    const create = vi.spyOn(api, 'createContest').mockResolvedValue(duel);
    open('/contests/new?format=duel');
    await userEvent.type(await screen.findByRole('textbox', { name: 'Название' }), 'Дуэль');
    await userEvent.type(screen.getByRole('searchbox', { name: 'Найти соперника по нику' }), opponent.handle);
    await userEvent.click(await screen.findByRole('button', { name: `Добавить ${opponent.handle}` }));
    await userEvent.click(screen.getByRole('radio', { name: /100. Сложение/ }));
    await userEvent.click(screen.getByRole('radio', { name: /200. Другая/ }));
    expect(screen.getByRole('radio', { name: /100. Сложение/ })).not.toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Создать дуэль' }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ format: 'duel', authorIds: [owner.id], opponentId: opponent.id, problemIds: ['second'] })));
  });
});

describe('Сравнение своих решений', () => {
  it('публикует выбранные посылки с предупреждением и объяснением', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue({ ...task, format: 'standard' });
    vi.spyOn(api, 'listContests').mockResolvedValue([]);
    vi.spyOn(api, 'listSubmissions').mockResolvedValue({ items: [{ ...answer, id: 'one', format: 'standard', timeMs: 0.1 }, { ...answer, id: 'two', format: 'standard', timeMs: 0.2 }], total: 2 });
    const create = vi.spyOn(api, 'createComparison').mockResolvedValue(task);
    open('/problem/vector');
    await userEvent.click(await screen.findByText('Сравнить мои решения'));
    expect(screen.getByText(/Оба выбранных исходных кода будут опубликованы/)).toBeVisible();
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Код 1: посылка' }), 'one');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Код 2: посылка' }), 'two');
    expect(screen.getByRole('button', { name: 'Опубликовать сравнение' })).toBeDisabled();
    await userEvent.type(screen.getByRole('textbox', { name: 'Объяснение' }), ' Последовательное чтение быстрее. ');
    await userEvent.click(screen.getByRole('button', { name: 'Опубликовать сравнение' }));
    expect(create).toHaveBeenCalledWith(task.id, { firstSubmissionId: 'one', secondSubmissionId: 'two', explanation: 'Последовательное чтение быстрее.' });
  });
});
