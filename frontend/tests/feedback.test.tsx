import { fireEvent, render, renderHook, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import type { ProblemDetail, User } from '../src/api/types';
import Problem from '../src/pages/Problem';
import { ErrorMessage, useFeedbackVisibility } from '../src/ui';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({
  default: ({ value, onChange, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; label?: string }) => <textarea aria-label={label} value={value} onChange={event => onChange?.(event.target.value)} />,
}));

const problem: ProblemDetail = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', tags: [],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null,
  bestTimeMs: null, recordTimeMs: null, statementMarkdown: 'Сложите два вектора.', starterCode: 'void solve() {}',
  timeLimitSeconds: 2, memoryLimitMb: 1024, environmentMarkdown: 'NVIDIA A100',
};

function feedback(top: number, bottom: number) {
  const element = document.createElement('div');
  const scroll = vi.fn();
  element.getBoundingClientRect = () => new DOMRect(0, top, 100, bottom - top);
  element.scrollIntoView = scroll;
  const hook = renderHook(({ trigger, enabled }) => useFeedbackVisibility(trigger, enabled), { initialProps: { trigger: null as unknown, enabled: true } });
  hook.result.current.current = element;
  return { ...hook, scroll };
}

beforeEach(() => {
  session.user = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
  vi.stubGlobal('innerHeight', 720);
  vi.stubGlobal('visualViewport', undefined);
});

afterEach(() => vi.unstubAllGlobals());

describe('Видимость ответа на действие', () => {
  it('позволяет дочитать длинную HTTP-ошибку с клавиатуры', async () => {
    render(<MemoryRouter><ErrorMessage error={new Error('Подробная диагностика. '.repeat(100))} /></MemoryRouter>);
    await userEvent.tab();
    expect(screen.getByText(/Подробная диагностика/)).toHaveFocus();
  });

  it('сохраняет положение страницы, если ответ уже виден', () => {
    const { rerender, scroll } = feedback(120, 200);
    rerender({ trigger: new Error('Не удалось отправить.'), enabled: true });
    expect(scroll).not.toHaveBeenCalled();
  });

  it('не прокручивает страницу из-за фоновых ошибок', () => {
    const { rerender, scroll } = feedback(800, 900);
    rerender({ trigger: new Error('Первый сбой фонового запроса.'), enabled: false });
    rerender({ trigger: new Error('Повторный сбой фонового запроса.'), enabled: false });
    expect(scroll).not.toHaveBeenCalled();
  });

  it('показывает новый ответ вне экрана один раз и не повторяет прокрутку при ререндере', () => {
    const { rerender, scroll } = feedback(700, 780);
    const error = new Error('Не удалось отправить.');
    rerender({ trigger: error, enabled: true });
    expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    rerender({ trigger: error, enabled: true });
    expect(scroll).toHaveBeenCalledTimes(1);
    rerender({ trigger: new Error('Ошибка следующей попытки.'), enabled: true });
    expect(scroll).toHaveBeenCalledTimes(2);
  });

  it('учитывает видимую часть окна при появлении экранной клавиатуры', () => {
    vi.stubGlobal('innerHeight', 844);
    vi.stubGlobal('visualViewport', { offsetTop: 80, height: 350 });
    const { rerender, scroll } = feedback(400, 470);
    rerender({ trigger: new Error('Ответ находится под клавиатурой.'), enabled: true });
    expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
  });
});

describe('Ответы редактора', () => {
  it.each(['edit', 'check'] as const)('убирает старую ошибку отправки после %s и показывает новый результат рядом с кнопками', async action => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    vi.spyOn(api, 'submitSolution').mockRejectedValue(new ApiError(503, 'Проверка временно недоступна.'));
    const diagnostic = Array.from({ length: 80 }, (_, index) => `Тест ${index}: ожидалось 1, получено 2.`).join('\n');
    const check = vi.spyOn(api, 'checkExample').mockResolvedValue({ passed: false, diagnostic });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/problem/vector-add']}><Routes><Route path="/problem/:id" element={<Problem />} /></Routes></MemoryRouter></QueryClientProvider>);
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    const send = screen.getByRole('button', { name: 'Отправить' });
    const footer = send.closest<HTMLElement>('.editor-footer');
    expect(footer).not.toBeNull();
    await userEvent.click(send);
    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('Проверка временно недоступна.');
    expect(footer).toContainElement(error);

    const code = action === 'edit' ? 'void solve() { changed(); }' : problem.starterCode;
    if (action === 'edit') {
      fireEvent.change(editor, { target: { value: code } });
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    }
    await userEvent.click(screen.getByRole('button', { name: 'Проверить пример' }));

    expect(await screen.findByText('Пример не пройден.')).toBeVisible();
    expect(check).toHaveBeenCalledWith(problem.id, 'practice', code);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(within(footer!).getByText('Пример не пройден.')).toBeVisible();
    expect(within(footer!).getByLabelText('Диагностика примера')).toHaveTextContent('Тест 79: ожидалось 1, получено 2.');
    expect(within(footer!).getByRole('button', { name: 'Отправить' })).toBeEnabled();
  });
});
