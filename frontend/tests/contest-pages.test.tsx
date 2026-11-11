import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest as ContestRecord, Problem, User } from '../src/api/types';
import Contest from '../src/pages/Contest';
import Contests from '../src/pages/Contests';
import Duels from '../src/pages/Duels';
import NewDuel from '../src/pages/NewDuel';

const session = vi.hoisted(() => ({ user: null as User | null }));

vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));

const author: User = { id: 'author', handle: 'contest_author', ratings: {} };
const participant: User = { ...author, id: 'participant', handle: 'new_cuda' };
const problem: Problem = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', format: 'standard', tags: ['Массивы'],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: null, bestScore: null, recordScore: null }],

};

function contest(phase: 'upcoming' | 'live' | 'finished'): ContestRecord {
  const now = Date.now();
  return {
    id: 'cuda-round', title: 'CUDA Round', format: 'standard', startAt: new Date(now + (phase === 'upcoming' ? 3600000 : -7200000)).toISOString(),
    endAt: new Date(now + (phase === 'finished' ? -3600000 : 7200000)).toISOString(), authors: [author],
    problemIds: [problem.id], problemCount: 1, registered: false, processor: 'gpu-cuda',
  };
}

function open(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/contests" element={<Contests />} />
    <Route path="/contest/:id" element={<Contest />} />
    <Route path="/duels" element={<Duels />} />
    <Route path="/duels/new" element={<NewDuel />} />
  </Routes></MemoryRouter></QueryClientProvider>);
}

beforeEach(() => { session.user = participant; });

describe('Список соревнований', () => {
  it('применяет поиск и фильтр по фазе, сохраняя значения', async () => {
    const list = vi.spyOn(api, 'listContests').mockResolvedValue([]);
    open('/contests');
    const input = screen.getByRole('searchbox', { name: 'Найти соревнование' });
    fireEvent.change(input, { target: { value: 'CUDA' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ q: 'CUDA' }, expect.any(AbortSignal)));

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Показать соревнования' }), 'finished');
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Показать соревнования' })).toHaveValue('finished'));
    expect(input).toHaveValue('CUDA');
    expect(list).toHaveBeenLastCalledWith({ q: 'CUDA' }, expect.any(AbortSignal));
  });

  it.each(['фильтра', 'поиска'])('показывает ошибку регистрации в нужной ячейке и сбрасывает при смене %s', async change => {
    const first = { ...contest('live'), id: 'first', title: 'Первый контест' };
    const second = { ...contest('live'), id: 'second', title: 'Второй контест' };
    vi.spyOn(api, 'listContests').mockResolvedValue([first, second]);
    const register = vi.spyOn(api, 'registerForContest').mockRejectedValue(new Error('Не удалось зарегистрироваться.'));
    open('/contests');
    const target = await screen.findByRole('row', { name: /Второй контест/ });
    const button = within(target).getByRole('button', { name: 'Зарегистрироваться' });
    await userEvent.click(button);
    const error = await within(target).findByRole('alert');
    expect(register).toHaveBeenCalledWith('second');
    expect(error).toHaveTextContent('Не удалось зарегистрироваться.');
    expect(error.closest('td')).toBe(button.closest('td'));
    expect(within(screen.getByRole('row', { name: /Первый контест/ })).queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);

    if (change === 'фильтра') {
      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Показать соревнования' }), 'upcoming');
      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Показать соревнования' }), 'now');
    } else {
      const input = screen.getByRole('searchbox', { name: 'Найти соревнование' });
      fireEvent.change(input, { target: { value: 'контест' } });
      fireEvent.keyDown(input, { key: 'Enter' });
    }
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getByRole('row', { name: /Второй контест/ })).toBeVisible();
  });

  it('группирует соревнования автора сверху', async () => {
    const mine = { ...contest('live'), id: 'mine', title: 'Мой контест', authors: [{ ...author, id: 'myself' }] };
    const other = { ...contest('live'), id: 'other', title: 'Чужой контест' };
    vi.spyOn(api, 'listContests').mockResolvedValue([other, mine]);
    session.user = { ...author, id: 'myself' };
    open('/contests');
    const rows = await screen.findAllByRole('row');
    const labels = rows.map(row => row.textContent);
    expect(labels.findIndex(label => label?.includes('Мой контест'))).toBeLessThan(labels.findIndex(label => label?.includes('Чужой контест')));
    expect(screen.getByRole('row', { name: /Мои соревнования/ })).toBeInTheDocument();
    // without a heading of their own the other contests would read as the author's too
    const others = labels.findIndex(label => label === 'Остальные соревнования');
    expect(others).toBeGreaterThan(labels.findIndex(label => label?.includes('Мой контест')));
    expect(others).toBeLessThan(labels.findIndex(label => label?.includes('Чужой контест')));
  });

  it('не делит список на группы, если своих соревнований нет', async () => {
    vi.spyOn(api, 'listContests').mockResolvedValue([{ ...contest('live'), title: 'Чужой контест' }]);
    session.user = { ...author, id: 'myself' };
    open('/contests');
    expect(await screen.findByRole('link', { name: 'Чужой контест' })).toBeVisible();
    expect(screen.queryByRole('row', { name: /Мои соревнования|Остальные соревнования/ })).not.toBeInTheDocument();
  });
});

describe('Доступ к задачам до начала', () => {
  it.each([
    { role: 'обычный участник', user: participant, visible: false },
    { role: 'автор', user: author, visible: true },
  ])('$role получает задачи только при наличии доступа', async ({ user, visible }) => {
    session.user = user;
    vi.spyOn(api, 'getContest').mockResolvedValue(contest('upcoming'));
    const tasks = vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
    open('/contest/cuda-round');
    await screen.findByRole('heading', { name: 'CUDA Round' });

    if (visible) {
      expect(await screen.findByRole('link', { name: 'A. Сложение векторов' })).toHaveAttribute('href', '/problem/vector-add?contest=cuda-round');
      expect(tasks).toHaveBeenCalledWith({ context: 'cuda-round' }, expect.any(AbortSignal));
    } else {
      expect(screen.getByText(/^Задачи откроются /)).toBeVisible();
      expect(screen.queryByRole('table', { name: 'Задачи соревнования' })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'A. Сложение векторов' })).not.toBeInTheDocument();
      expect(tasks).not.toHaveBeenCalled();
    }
  });
});

describe('Личные результаты контеста', () => {
  it.each(['live', 'finished'] as const)('позволяет открыть новую дуэль из %s и вернуться через список без сдачи', async phase => {
    const room = { ...contest(phase), format: 'duel' as const, registered: true, challengerId: participant.id, opponentId: author.id };
    vi.spyOn(api, 'getContest').mockResolvedValue(room);
    vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([]);
    vi.spyOn(api, 'listDuels').mockResolvedValue({ items: [{ id: 'request', mode: 'invite', processor: room.processor, status: 'matched', creator: participant, opponent: author, contestId: room.id, createdAt: room.startAt, expiresAt: room.endAt, problemId: problem.id, problemTitle: problem.title, difficulty: problem.difficulty }], total: 1 });
    const surrender = vi.spyOn(api, 'surrenderContest');
    open('/contest/cuda-round');
    expect(await screen.findByRole('link', { name: 'Мои дуэли' })).toHaveAttribute('href', '/duels');
    await userEvent.click(screen.getByRole('link', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('heading', { name: 'Новая дуэль' })).toBeVisible();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Найти соперника' })).toBeEnabled());
    await userEvent.click(screen.getByRole('link', { name: 'Мои дуэли' }));
    if (phase === 'finished') await userEvent.click(screen.getByRole('link', { name: 'История' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Открыть дуэль' }));
    expect(await screen.findByRole('heading', { name: room.title })).toBeVisible();
    expect(surrender).not.toHaveBeenCalled();
  });

  it('не открывает дорешивание, взломы и виртуальное участие для завершенной дуэли', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue({ ...contest('finished'), format: 'duel', finalized: true, hackEndAt: new Date(Date.now() + 3600000).toISOString(), challengerId: participant.id, opponentId: author.id });
    const tasks = vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([]);
    const hacks = vi.spyOn(api, 'listHacks');
    open('/contest/cuda-round?mode=upsolving&tab=hacks');
    expect(await screen.findByRole('link', { name: 'Сложение векторов' })).toHaveAttribute('href', '/problem/vector-add?contest=cuda-round');
    expect(tasks).toHaveBeenCalledWith({ context: 'cuda-round' }, expect.any(AbortSignal));
    expect(screen.queryByRole('button', { name: 'Начать виртуально' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Дорешивание' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Взломы' })).not.toBeInTheDocument();
    expect(hacks).not.toHaveBeenCalled();
  });

  it('дорешивание использует отдельный контекст и ссылки на архивные задачи', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue({ ...contest('finished'), finalized: true });
    const tasks = vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
    open('/contest/cuda-round?mode=upsolving');
    expect(await screen.findByRole('link', { name: 'A. Сложение векторов' })).toHaveAttribute('href', '/problem/vector-add?contest=upsolve%3Acuda-round');
    expect(tasks).toHaveBeenCalledWith({ context: 'upsolve:cuda-round' }, expect.any(AbortSignal));
    expect(screen.getByRole('button', { name: 'Начать виртуально' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Официальные результаты' })).toBeVisible();
  });

  it.each([
    { status: 'wrong_answer', label: 'Неверный ответ', phase: 'finished' },
    { status: 'queued', label: 'В очереди', phase: 'live' },
  ] as const)('сохраняет принятое решение при последней посылке $status', async ({ status, label, phase }) => {
    vi.spyOn(api, 'getContest').mockResolvedValue(contest(phase));
    vi.spyOn(api, 'allProblems').mockResolvedValue([{
      ...problem, solved: true, lastSubmissionId: 'latest', lastStatus: status,
      processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: 'best', bestScore: { value: 0.1234, type: 'TIME_MS' }, recordScore: { value: 0.05, type: 'TIME_MS' } }],
    }]);
    open('/contest/cuda-round');
    const table = await screen.findByRole('table', { name: 'Задачи соревнования' });
    expect(within(table).getByRole('link', { name: 'A. Сложение векторов' })).toHaveAttribute('href', '/problem/vector-add?contest=cuda-round');
    expect(within(table).getByRole('link', { name: 'Принято' })).toHaveAttribute('href', '/submission/best');
    // GPU time does not count in a contest, so the task list does not show it.
    expect(within(table).queryByText('0.1234')).not.toBeInTheDocument();
    expect(within(table).getByRole('link', { name: `Последняя: ${label}` })).toHaveAttribute('href', '/submission/latest');
  });
});

describe('Таблица контеста', () => {
  it('учитывает серверные часы и продолжает опрос обычного контеста до финализации', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    try {
      let finalized = false;
      const round = { ...contest('live'), startAt: '2026-01-02T09:00:00Z', endAt: '2026-01-02T10:00:02Z', hackEndAt: '2026-01-02T11:00:00Z' };
      const get = vi.spyOn(api, 'getContest').mockImplementation(async () => ({ ...round, finalized, serverNow: new Date(Date.now() + 86400000).toISOString() }));
      vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
      vi.spyOn(api, 'contestStandings').mockResolvedValue([]);
      open('/contest/cuda-round?tab=standings');
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByText(/До конца/)).toHaveTextContent('00:00:02');
      await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
      expect(screen.getByText(/Период взломов до/)).toBeVisible();
      expect(screen.queryByRole('button', { name: 'Начать виртуально' })).not.toBeInTheDocument();
      finalized = true;
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(screen.getByRole('button', { name: 'Начать виртуально' })).toBeVisible();
      expect(screen.queryByText(/Период взломов до/)).not.toBeInTheDocument();
      const requests = get.mock.calls.length;
      await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
      expect(get).toHaveBeenCalledTimes(requests);
    } finally { vi.useRealTimers(); }
  });

  it.each(['poll', 'retry'])('получает финальный снимок дуэли после ошибки последнего GET: %s', async recovery => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    try {
      const room: ContestRecord = { ...contest('live'), format: 'duel', challengerId: participant.id, opponentId: author.id, registered: true, endAt: '2026-01-01T10:00:02Z' };
      const get = vi.spyOn(api, 'getContest').mockResolvedValueOnce(room).mockRejectedValueOnce(new Error('Не удалось обновить дуэль.')).mockResolvedValue(room);
      vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
      vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: participant, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 1, type: 'TIME_MS' } }, { rank: 2, user: author, solved: 0, totalSeconds: 0, times: {} }]);
      open('/contest/cuda-round');
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByText('Не удалось обновить дуэль.')).toBeVisible();
      expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
      const retry = screen.getByRole('button', { name: 'Повторить загрузку соревнования' });
      if (recovery === 'retry') await act(async () => { fireEvent.click(retry); await vi.advanceTimersByTimeAsync(100); });
      else await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(screen.getByText(/Победитель:/)).toHaveTextContent(participant.handle);
      expect(get).toHaveBeenCalledTimes(3);
      await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
      expect(get).toHaveBeenCalledTimes(3);
    } finally { vi.useRealTimers(); }
  });

  it('не считает ответ начатого до таймера запроса финальным снимком', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    try {
      const room: ContestRecord = { ...contest('live'), format: 'duel', challengerId: participant.id, opponentId: author.id, registered: true, endAt: '2026-01-01T10:00:02Z' };
      let finish!: (value: ContestRecord) => void;
      const get = vi.spyOn(api, 'getContest').mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue(room);
      vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
      vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: participant, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 1, type: 'TIME_MS' } }, { rank: 2, user: author, solved: 0, totalSeconds: 0, times: {} }]);
      open('/contest/cuda-round');
      await act(async () => { await vi.advanceTimersByTimeAsync(2100); finish(room); });
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(get).toHaveBeenCalledTimes(2);
      expect(screen.getByText(/Победитель:/)).toHaveTextContent(participant.handle);
    } finally { vi.useRealTimers(); }
  });

  it('ранжирует по решенным задачам и времени сдачи без времени GPU', async () => {
    vi.spyOn(api, 'getContest').mockResolvedValue(contest('live'));
    vi.spyOn(api, 'allProblems').mockResolvedValue([problem]);
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{
      rank: 1, user: { id: 'alice', handle: 'alice', ratings: {} },
      solved: 1, totalSeconds: 4512, times: { 'vector-add': 4512 },
    }]);
    open('/contest/cuda-round?tab=standings');
    const table = await screen.findByRole('table', { name: 'Таблица контеста' });
    expect(within(table).getByRole('columnheader', { name: 'Время' })).toBeVisible();
    expect(within(table).queryByText(/ускорен/i)).not.toBeInTheDocument();
    expect(within(table).getAllByText('1:15:12')).toHaveLength(2);
  });
});
