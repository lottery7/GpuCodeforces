import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

const owner: User = { id: 'owner', handle: 'cuda_user', ratings: {} };
const starter = 'void solve() {}';
const problem: ProblemDetail = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', format: 'standard', tags: ['Массивы'],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: null, bestScore: null, recordScore: null }],
  versionId: 'vector-add-v1', statementMarkdown: 'Сложите два вектора.', memoryLimitMb: 1024,
  processorDetails: [{ id: 'gpu-cuda', starters: { cuda: 'void solve() {}' }, limit: { value: 2000, type: 'TIME_MS' }, environmentMarkdown: 'NVIDIA A100' }],
};
const record: SubmissionRecord = {
  id: '42', problemId: problem.id, problemTitle: problem.title, format: 'standard', user: owner,
  contestId: 'finished-contest', contestTitle: 'Прошедшее соревнование', createdAt: '2026-01-01T10:00:00Z',
  status: 'measurement_error', processor: 'gpu-cuda', language: 'cuda', score: null, comparable: false, code: 'void solve() {}', diagnostic: 'GPU недоступна.',
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
  it('повторяет финальный GET дуэли после ошибки, не объявляя результат по старому снимку', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    try {
      const opponent = { ...owner, id: 'opponent', handle: 'opponent' };
      const room: Contest = { id: 'room', title: 'Дуэль', format: 'duel', challengerId: owner.id, opponentId: opponent.id, startAt: '2026-01-01T09:00:00Z', endAt: '2026-01-01T10:00:02Z', authors: [owner], registered: true, processor: 'gpu-cuda', problemIds: [problem.id], problemCount: 1 };
      const get = vi.spyOn(api, 'getContest').mockResolvedValueOnce(room).mockRejectedValueOnce(new Error('Нет связи с соревнованием.')).mockResolvedValue(room);
      vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
      vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: owner, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 1, type: 'TIME_MS' } }, { rank: 2, user: opponent, solved: 0, totalSeconds: 0, times: {} }]);
      open('/problem/vector-add?contest=room');
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
      await act(async () => { await vi.advanceTimersByTimeAsync(100); });
      expect(screen.getByText('Нет связи с соревнованием.')).toBeVisible();
      expect(screen.getByRole('button', { name: 'Повторить загрузку соревнования' })).toBeEnabled();
      expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(get).toHaveBeenCalledTimes(3);
      expect(screen.getByText(/Победитель:/)).toHaveTextContent(owner.handle);
    } finally { vi.useRealTimers(); }
  });

  it('не показывает результат примера как актуальный после правки кода', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    let finish!: (result: { passed: boolean; diagnostic: string }) => void;
    const check = vi.spyOn(api, 'checkExample').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    open('/problem/vector-add');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    await userEvent.click(screen.getByRole('button', { name: 'Проверить пример' }));
    expect(check).toHaveBeenCalledWith(problem.id, 'practice', { code: starter, processor: 'gpu-cuda', language: 'cuda' });
    fireEvent.change(editor, { target: { value: 'void solve() { changed(); }' } });
    await act(async () => finish({ passed: true, diagnostic: '' }));
    expect(await screen.findByText('Код изменен. Проверьте пример снова.')).toBeVisible();
    expect(screen.queryByText('Пример пройден.')).not.toBeInTheDocument();
  });

  it('обновляет лидеров на границе окончания контеста и останавливает опрос', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    const contest: Contest = { id: 'round', title: 'Контест', format: 'standard', startAt: '2026-01-01T09:00:00Z', endAt: '2026-01-01T10:00:02Z', authors: [owner], problemIds: [problem.id], problemCount: 1, registered: true, processor: 'gpu-cuda' };
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

describe('Процессоры задачи', () => {
  const twoProcessors: ProblemDetail = {
    ...problem,
    processors: [...problem.processors, { id: 'rtl', languages: ['systemverilog'], bestSubmissionId: null, bestScore: null, recordScore: null }],
    processorDetails: [...problem.processorDetails, {
      id: 'rtl', starters: { systemverilog: 'module solve; endmodule' }, limit: { value: 100000, type: 'CYCLES_X_DEPTH_X_TRANSISTORS' }, environmentMarkdown: 'Verilator + Yosys',
    }],
  };

  it('берет шаблон, лимит, окружение и лидеров выбранного процессора и отправляет на него', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(twoProcessors);
    const leaders = vi.spyOn(api, 'problemLeaders').mockResolvedValue([]);
    const submit = vi.spyOn(api, 'submitSolution').mockResolvedValue({ ...record, id: '43', processor: 'rtl', language: 'systemverilog' });
    open('/problem/vector-add');
    expect(await screen.findByText(/Лимит: 2000 мс/)).toBeVisible();

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Процессор' }), 'rtl');

    expect(await screen.findByLabelText('Код решения на C++ и CUDA')).toHaveValue('module solve; endmodule');
    expect(screen.getByText(/Лимит: 100\s000 такты × глубина × транзисторы/)).toBeVisible();
    expect(screen.getByText('solution.sv')).toBeVisible();

    // the choice survives switching tabs
    await userEvent.click(screen.getByRole('link', { name: 'Лидеры' }));
    await waitFor(() => expect(leaders).toHaveBeenCalledWith(problem.id, 'rtl', 'practice', expect.anything()));
    await userEvent.click(screen.getByRole('link', { name: 'Условие и решение' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Отправить' }));
    expect(submit).toHaveBeenCalledWith({ problemId: problem.id, processor: 'rtl', language: 'systemverilog', code: 'module solve; endmodule' });
  });

  it('показывает score посылки в единицах ее процессора и дополнительные показатели', async () => {
    vi.spyOn(api, 'getSubmission').mockResolvedValue({
      ...record, status: 'accepted', processor: 'rtl', language: 'systemverilog', comparable: true,
      score: { value: 4096, type: 'CYCLES_X_DEPTH_X_TRANSISTORS' }, metrics: { cycles: 64, depth: 4, transistors: 16, cells: 6 },
    });
    open('/submission/42');
    expect(await screen.findByText(/Результат:/)).toBeVisible();
    expect(screen.getByText('такты × глубина × транзисторы', { exact: false })).toBeVisible();
    expect(screen.getByText('Глубина логики')).toBeVisible();
    expect(screen.getByText('Транзисторы')).toBeVisible();
    expect(screen.queryByText(/Время GPU/)).not.toBeInTheDocument();
  });

  it('переносит код из завершенной дуэли в практику на том же процессоре и языке', async () => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(twoProcessors);
    vi.spyOn(api, 'getContest').mockResolvedValue({ id: 'finished', title: 'Дуэль', format: 'duel', processor: 'rtl', startAt: new Date(Date.now() - 7200000).toISOString(), endAt: new Date(Date.now() - 1000).toISOString(), authors: [owner], problemIds: [problem.id], problemCount: 1, registered: true });
    vi.spyOn(api, 'contestStandings').mockResolvedValue([]);
    const submit = vi.spyOn(api, 'submitSolution').mockResolvedValue({ ...record, id: '43', contestId: null, processor: 'rtl', language: 'systemverilog' });
    open('/problem/vector-add?contest=finished&processor=rtl&language=systemverilog');
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    fireEvent.change(editor, { target: { value: 'module solve; wire changed; endmodule' } });
    await userEvent.click(await screen.findByRole('button', { name: 'Продолжить в практике' }));
    expect(await screen.findByText('solution.sv')).toBeVisible();
    expect(screen.getByLabelText('Код решения на C++ и CUDA')).toHaveValue('module solve; wire changed; endmodule');
    await userEvent.click(await screen.findByRole('button', { name: 'Отправить' }));
    expect(submit).toHaveBeenCalledWith({ problemId: problem.id, processor: 'rtl', language: 'systemverilog', code: 'module solve; wire changed; endmodule' });
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
