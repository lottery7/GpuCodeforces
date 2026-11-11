import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import type { ProblemDetail, User } from '../src/api/types';
import Problem from '../src/pages/Problem';
import { readDraft } from '../src/drafts';
import { ErrorMessage, QueryState, useFeedbackVisibility } from '../src/ui';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({
  default: ({ value, onChange, label = 'Код решения на C++ и CUDA' }: { value: string; onChange?: (value: string) => void; label?: string }) => <textarea aria-label={label} value={value} onChange={event => onChange?.(event.target.value)} />,
}));

const starter = 'void solve() {}';
const problem: ProblemDetail = {
  id: 'vector-add', code: '100', title: 'Сложение векторов', difficulty: 'easy', format: 'standard', tags: [],
  solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: null, bestScore: null, recordScore: null }],
  versionId: 'vector-add-v1', statementMarkdown: 'Сложите два вектора.', memoryLimitMb: 1024,
  processorDetails: [{ id: 'gpu-cuda', starters: { cuda: 'void solve() {}' }, limit: { value: 2000, type: 'TIME_MS' }, environmentMarkdown: 'NVIDIA A100' }],
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
  session.user = { id: 'owner', handle: 'cuda_user', ratings: {} };
  vi.stubGlobal('innerHeight', 720);
  vi.stubGlobal('visualViewport', undefined);
});

afterEach(() => vi.unstubAllGlobals());

describe('Ошибка загрузки страницы', () => {
  function failed(error: unknown) {
    const refetch = vi.fn();
    render(<MemoryRouter><QueryState query={{ error, refetch }} /></MemoryRouter>);
    return refetch;
  }

  it('предлагает повторить запрос после временного сбоя', async () => {
    const refetch = failed(new ApiError(503, 'Сервис недоступен'));
    await userEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('не предлагает повторять запрос того, чего нет', () => {
    failed(new ApiError(404, 'Задача не найдена'));
    expect(screen.getByRole('alert')).toHaveTextContent('Задача не найдена');
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(document.title).toBe('GPU Forces - Не найдено');
  });
});

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
  it.each(['error', 'example'] as const)('закрывает %s, сохраняет черновик и возвращает фокус к условию', async output => {
    vi.spyOn(api, 'getProblem').mockResolvedValue(problem);
    vi.spyOn(api, 'submitSolution').mockRejectedValue(new ApiError(503, 'Проверка временно недоступна.'));
    let finish!: (value: { passed: boolean; diagnostic: string }) => void;
    vi.spyOn(api, 'checkExample').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/problem/vector-add']}><Routes><Route path="/problem/:id" element={<Problem />} /></Routes></MemoryRouter></QueryClientProvider>);
    const editor = await screen.findByLabelText('Код решения на C++ и CUDA');
    const code = 'void solve() { keep_my_solution(); }';
    fireEvent.change(editor, { target: { value: code } });
    const statement = screen.getByRole('region', { name: 'Условие задачи' });
    statement.getBoundingClientRect = () => output === 'error' ? new DOMRect(0, 100, 400, 300) : new DOMRect(0, -306, 400, 687);
    const scroll = vi.fn();
    statement.scrollIntoView = scroll;
    expect(screen.queryByRole('button', { name: 'Закрыть вывод' })).not.toBeInTheDocument();

    if (output === 'error') {
      await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Проверка временно недоступна.');
    } else {
      await userEvent.click(screen.getByRole('button', { name: 'Проверить пример' }));
      expect(screen.queryByRole('button', { name: 'Закрыть вывод' })).not.toBeInTheDocument();
      await act(async () => finish({ passed: false, diagnostic: 'Результат не совпадает с ожидаемым.' }));
      expect(await screen.findByLabelText('Диагностика примера')).toBeVisible();
    }
    await userEvent.click(await screen.findByRole('button', { name: 'Закрыть вывод' }));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Вывод проверки' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Закрыть вывод' })).not.toBeInTheDocument();
    expect(editor).toHaveValue(code);
    expect(readDraft('owner', { problemId: problem.id, context: 'practice', processor: 'gpu-cuda', language: 'cuda' }, starter)).toBe(code);
    await waitFor(() => expect(statement).toHaveFocus());
    if (output === 'error') expect(scroll).not.toHaveBeenCalled();
    else expect(scroll).toHaveBeenCalledExactlyOnceWith({ block: 'start', inline: 'nearest', behavior: 'instant' });
  });

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

    const code = action === 'edit' ? 'void solve() { changed(); }' : starter;
    if (action === 'edit') {
      fireEvent.change(editor, { target: { value: code } });
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    }
    await userEvent.click(screen.getByRole('button', { name: 'Проверить пример' }));

    expect(await screen.findByText('Пример не пройден.')).toBeVisible();
    expect(check).toHaveBeenCalledWith(problem.id, 'practice', { code, processor: 'gpu-cuda', language: 'cuda' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(within(footer!).getByText('Пример не пройден.')).toBeVisible();
    expect(within(footer!).getByLabelText('Диагностика примера')).toHaveTextContent('Тест 79: ожидалось 1, получено 2.');
    expect(within(footer!).getByRole('button', { name: 'Отправить' })).toBeEnabled();
  });
});
