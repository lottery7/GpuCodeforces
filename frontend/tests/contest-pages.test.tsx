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

const author: User = { id: 'author', handle: 'contest_author', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
const participant: User = { ...author, id: 'participant', handle: 'new_cuda' };
const problem: Problem = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', tags: ['Массивы'],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null,
  bestTimeMs: null, recordTimeMs: null,
};

function contest(phase: 'upcoming' | 'live' | 'finished'): ContestRecord {
  const now = Date.now();
  return {
    id: 'cuda-round', title: 'CUDA Round', startAt: new Date(now + (phase === 'upcoming' ? 3600000 : -7200000)).toISOString(),
    endAt: new Date(now + (phase === 'finished' ? -3600000 : 7200000)).toISOString(), authors: [author],
    problemIds: [problem.id], problemCount: 1, registered: false, gpu: 'NVIDIA A100',
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
      bestSubmissionId: 'best', bestTimeMs: 0.1234, recordTimeMs: 0.05,
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
      rank: 1, user: { id: 'alice', handle: 'alice', rating: null, rank: null, ratingDelta: null, ratingContestId: null },
      solved: 1, totalSeconds: 4512, times: { 'vector-add': 4512 },
    }]);
    open('/contest/cuda-round?tab=standings');
    const table = await screen.findByRole('table', { name: 'Таблица контеста' });
    expect(within(table).getByRole('columnheader', { name: 'Время' })).toBeVisible();
    expect(within(table).queryByText(/ускорен/i)).not.toBeInTheDocument();
    expect(within(table).getAllByText('1:15:12')).toHaveLength(2);
  });
});
