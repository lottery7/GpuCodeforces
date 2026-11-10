import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest, ProblemDetail, Submission as SubmissionRecord, User } from '../src/api/types';
import Problem from '../src/pages/Problem';
import Submission from '../src/pages/Submission';

const session = vi.hoisted(() => ({ user: null as User | null }));

vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({
  default: ({ value, onChange, readOnly, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; readOnly?: boolean; label?: string }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} />,
}));

const owner: User = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
const problem: ProblemDetail = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', tags: ['Массивы'],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null,
  bestTimeMs: null, recordTimeMs: null, statementMarkdown: 'Сложите два вектора.', starterCode: 'void solve() {}',
  timeLimitSeconds: 2, memoryLimitMb: 1024, environmentMarkdown: 'NVIDIA A100',
};
const record: SubmissionRecord = {
  id: '42', problemId: problem.id, problemTitle: problem.title, user: owner,
  contestId: 'finished-contest', contestTitle: 'Прошедшее соревнование', createdAt: '2026-01-01T10:00:00Z',
  status: 'measurement_error', timeMs: null, comparable: false, code: 'void solve() {}', diagnostic: 'GPU недоступна.',
};

function open(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/problem/:id" element={<Problem />} />
    <Route path="/submission/:id" element={<Submission />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

beforeEach(() => { session.user = owner; });

describe('Решение задачи', () => {
  it('не показывает результат примера как актуальный после правки кода', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    let finish!: (result: { passed: boolean; diagnostic: string }) => void;
    const check = vi.spyOn(api, 'checkExample').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    open('/problem/vector-add');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    await userEvent.click(screen.getByRole('button', { name: 'Проверить пример' }));
    expect(check).toHaveBeenCalledWith(problem.id, 'practice', problem.starterCode);
    fireEvent.change(editor, { target: { value: 'void solve() { changed(); }' } });
    await act(async () => finish({ passed: true, diagnostic: '' }));
    expect(await screen.findByText('Код изменен. Проверьте пример снова.')).toBeVisible();
    expect(screen.queryByText('Пример пройден.')).not.toBeInTheDocument();
  });

  it('обновляет лидеров на границе окончания контеста и останавливает опрос', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const contest: Contest = { id: 'round', title: 'Контест', startAt: '2026-01-01T09:00:00Z', endAt: '2026-01-01T10:00:02Z', authors: [owner], problemIds: [problem.id], problemCount: 1, registered: true, gpu: 'NVIDIA A100' };
    vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    const getContest = vi.spyOn(api, 'getContest').mockResolvedValue(contest);
    const leaders = vi.spyOn(api, 'problemLeaders').mockResolvedValue([]);
    try {
      open('/problem/vector-add?contest=round&tab=leaders');
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(leaders).toHaveBeenCalledTimes(1);
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(screen.getByText('Соревнование завершено. Новые решения в практике не изменят его результаты.')).toBeVisible();
      expect(leaders).toHaveBeenCalledTimes(2);
      expect(getContest).toHaveBeenCalledTimes(2);
      await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
      expect(leaders).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('Повторная проверка посылки', () => {
  it.each(['system_error', 'measurement_error'] as const)('разрешена автору после окончания контеста при %s', async status => {
    vi.spyOn(api, 'getSubmission').mockResolvedValue({ ...record, status });
    const recheck = vi.spyOn(api, 'recheckSubmission').mockResolvedValue({ ...record, status: 'queued' });
    open('/submission/42');
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить проверку' }));
    expect(recheck).toHaveBeenCalledWith('42');
    expect(await screen.findByText('В очереди')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Повторить проверку' })).not.toBeInTheDocument();
  });

  it('не предлагает повторную проверку и не раскрывает код другому пользователю', async () => {
    session.user = { ...owner, id: 'viewer', handle: 'viewer' };
    vi.spyOn(api, 'getSubmission').mockResolvedValue(record);
    const recheck = vi.spyOn(api, 'recheckSubmission');
    open('/submission/42');
    expect(await screen.findByRole('heading', { name: 'Посылка #42' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Повторить проверку' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Исходный код посылки')).not.toBeInTheDocument();
    expect(screen.queryByText('GPU недоступна.')).not.toBeInTheDocument();
    expect(recheck).not.toHaveBeenCalled();
  });
});
