import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest as ContestRecord, Problem, User } from '../src/api/types';
import Contest from '../src/pages/Contest';
import Contests from '../src/pages/Contests';

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
  </Routes></MemoryRouter></QueryClientProvider>);
}

beforeEach(() => { session.user = participant; });

describe('Список соревнований', () => {
  it('сохраняет поиск при смене вкладок и применяет его к нужной фазе', async () => {
    const list = vi.spyOn(api, 'listContests').mockResolvedValue([]);
    open('/contests?tabs=live');
    const input = screen.getByRole('searchbox', { name: 'Найти соревнование' });
    fireEvent.change(input, { target: { value: 'CUDA' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ phase: 'live', q: 'CUDA' }, expect.any(AbortSignal)));

    await userEvent.click(screen.getByRole('link', { name: 'Будущие' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ phase: 'upcoming', q: 'CUDA' }, expect.any(AbortSignal)));
    expect(input).toHaveValue('CUDA');

    await userEvent.click(screen.getByRole('link', { name: 'Завершенные' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ phase: 'finished', q: 'CUDA' }, expect.any(AbortSignal)));
    expect(input).toHaveValue('CUDA');

    await userEvent.click(screen.getByRole('link', { name: 'Мои' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ mine: true, q: 'CUDA' }, expect.any(AbortSignal)));
    expect(input).toHaveValue('CUDA');
    expect(screen.getByRole('link', { name: 'Текущие' })).toHaveAttribute('href', '/contests?tabs=live&q=CUDA');
  });

  it.each(['вкладки', 'поиска'])('показывает ошибку регистрации в нужной ячейке и сбрасывает при смене %s', async change => {
    const first = { ...contest('live'), id: 'first', title: 'Первый контест' };
    const second = { ...contest('live'), id: 'second', title: 'Второй контест' };
    vi.spyOn(api, 'listContests').mockResolvedValue([first, second]);
    const register = vi.spyOn(api, 'registerForContest').mockRejectedValue(new Error('Не удалось зарегистрироваться.'));
    open('/contests?tabs=live');
    const target = await screen.findByRole('row', { name: /Второй контест/ });
    const button = within(target).getByRole('button', { name: 'Зарегистрироваться' });
    await userEvent.click(button);
    const error = await within(target).findByRole('alert');
    expect(register).toHaveBeenCalledWith('second');
    expect(error).toHaveTextContent('Не удалось зарегистрироваться.');
    expect(error.closest('td')).toBe(button.closest('td'));
    expect(within(screen.getByRole('row', { name: /Первый контест/ })).queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);

    if (change === 'вкладки') {
      await userEvent.click(screen.getByRole('link', { name: 'Будущие' }));
      await userEvent.click(screen.getByRole('link', { name: 'Текущие' }));
    } else {
      const input = screen.getByRole('searchbox', { name: 'Найти соревнование' });
      fireEvent.change(input, { target: { value: 'контест' } });
      fireEvent.keyDown(input, { key: 'Enter' });
    }
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getByRole('row', { name: /Второй контест/ })).toBeVisible();
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
