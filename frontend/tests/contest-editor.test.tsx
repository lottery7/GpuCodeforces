import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import type { Contest, Problem, User } from '../src/api/types';
import ContestEditor from '../src/pages/ContestEditor';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));

function user(id: string, handle: string): User {
  return { id, handle, ratings: {} };
}

function problem(id: string, code: string, title: string): Problem {
  return { id, code, title, difficulty: 'easy', format: 'standard', tags: ['Массивы'], solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: null, bestScore: null, recordScore: null }] };
}

const creator = user('creator', 'creator_cuda');
const zeta = user('zeta', 'zeta_cuda');
const alpha = user('alpha', 'alpha_cuda');
const tasks = [problem('add', '100', 'Сложение векторов'), problem('reduce', '200', 'Редукция'), problem('transpose', '300', 'Транспонирование')];
const scrollIntoView = vi.fn();
const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');

function futureStart(days = 1) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 16) + ':00.000Z';
}

function contest(): Contest {
  const startAt = futureStart();
  return { id: 'round', title: 'Исходный контест', format: 'standard', startAt, endAt: new Date(Date.parse(startAt) + 120 * 60000).toISOString(), authors: [creator, zeta], problemIds: ['add', 'reduce'], problemCount: 2, registered: false, processor: 'gpu-cuda' };
}

function open(path = '/contests/new') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/contests/new" element={<ContestEditor />} />
    <Route path="/contest/:id/edit" element={<ContestEditor />} />
    <Route path="/contest/:id" element={<p>Контест сохранен</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

async function addAuthor(handle: string) {
  await userEvent.type(screen.getByRole('searchbox', { name: 'Найти автора по нику' }), handle);
  await userEvent.click(await screen.findByRole('button', { name: `Добавить ${handle}` }));
}

beforeEach(() => {
  session.user = creator;
  scrollIntoView.mockClear();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, window.innerHeight + 100, 200, 40));
  vi.spyOn(api, 'allProblems').mockResolvedValue(tasks);
});

afterEach(() => {
  if (originalScroll) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});

describe('Создание контеста', () => {
  it('ищет авторов по нику и позволяет убрать создателя, сохранив хотя бы одного автора', async () => {
    const search = vi.spyOn(api, 'listUsers').mockResolvedValue({ items: [zeta], total: 1 });
    open();
    expect(await screen.findByRole('button', { name: `Убрать ${creator.handle}` })).toBeDisabled();
    expect(search).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: `Добавить ${zeta.handle}` })).not.toBeInTheDocument();

    await addAuthor(zeta.handle);

    expect(search).toHaveBeenLastCalledWith({ q: zeta.handle, sort: 'handle', limit: 8 }, expect.objectContaining({ aborted: false }));
    await userEvent.click(screen.getByRole('button', { name: `Убрать ${creator.handle}` }));
    expect(screen.queryByRole('button', { name: `Убрать ${creator.handle}` })).not.toBeInTheDocument();
    const lastAuthor = screen.getByRole('button', { name: `Убрать ${zeta.handle}` });
    expect(lastAuthor).toBeDisabled();
    await userEvent.click(lastAuthor);
    expect(screen.getByRole('link', { name: zeta.handle })).toBeVisible();
  });

  it('отправляет авторов и выбранные задачи в заданном пользователем порядке', async () => {
    vi.spyOn(api, 'listUsers').mockImplementation(async (query = {}) => {
      const items = [zeta, alpha].filter(person => person.handle.includes(query.q || ''));
      return { items, total: items.length };
    });
    const create = vi.spyOn(api, 'createContest').mockResolvedValue({ ...contest(), id: 'created-round' });
    open();
    const title = await screen.findByRole('textbox', { name: 'Название' });
    await userEvent.type(title, '  GPU Round  ');
    const startAt = futureStart();
    fireEvent.change(screen.getByLabelText('Начало (МСК)'), { target: { value: new Date(Date.parse(startAt) + 3 * 3600000).toISOString().slice(0, 16) } });
    await userEvent.clear(screen.getByRole('spinbutton', { name: 'Длительность, минут' }));
    await userEvent.type(screen.getByRole('spinbutton', { name: 'Длительность, минут' }), '90');
    await addAuthor(zeta.handle);
    await addAuthor(alpha.handle);
    await userEvent.click(screen.getByRole('button', { name: `Убрать ${creator.handle}` }));
    await userEvent.click(screen.getByRole('checkbox', { name: /300\. Транспонирование/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /200\. Редукция/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Только выбранные' }));
    await userEvent.click(screen.getByRole('button', { name: 'Поднять Редукция' }));
    await userEvent.click(screen.getByRole('button', { name: 'Создать контест' }));

    await waitFor(() => expect(create).toHaveBeenCalledWith({
      title: 'GPU Round', format: 'standard', startAt, endAt: new Date(Date.parse(startAt) + 90 * 60000).toISOString(),
      authorIds: [zeta.id, alpha.id], problemIds: ['transpose', 'reduce', 'add'], processor: 'gpu-cuda',
    }));
    expect(await screen.findByText('Контест сохранен')).toBeVisible();
  });

  it('сохраняет локальные правки формы при фоновом обновлении контеста', async () => {
    const original = contest();
    const getContest = vi.spyOn(api, 'getContest').mockResolvedValue(original);
    vi.spyOn(api, 'listUsers').mockResolvedValue({ items: [alpha], total: 1 });
    const update = vi.spyOn(api, 'updateContest').mockResolvedValue(original);
    const client = open('/contest/round/edit');
    const title = await screen.findByRole('textbox', { name: 'Название' });
    await userEvent.clear(title);
    await userEvent.type(title, 'Мои изменения');
    const duration = screen.getByRole('spinbutton', { name: 'Длительность, минут' });
    await userEvent.clear(duration);
    await userEvent.type(duration, '75');
    await userEvent.click(screen.getByRole('button', { name: `Убрать ${zeta.handle}` }));
    await addAuthor(alpha.handle);
    await userEvent.click(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /300\. Транспонирование/ }));
    getContest.mockResolvedValue({ ...original, title: 'Обновление с сервера', authors: [creator], problemIds: ['add'], problemCount: 1 });

    await act(async () => { await client.invalidateQueries({ queryKey: ['contest', original.id, creator.id], exact: true }); });

    expect(getContest).toHaveBeenCalledTimes(2);
    expect(title).toHaveValue('Мои изменения');
    expect(duration).toHaveValue(75);
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(update).toHaveBeenCalledWith(original.id, {
      title: 'Мои изменения', format: 'standard', startAt: original.startAt, endAt: new Date(Date.parse(original.startAt) + 75 * 60000).toISOString(),
      authorIds: [creator.id, alpha.id], problemIds: ['reduce', 'transpose'], processor: 'gpu-cuda',
    }));
  });

  it('берет в контест на процессоре только задачи, которые на нем сдаются', async () => {
    const onRtl = { ...problem('chip', '400', 'Сумматор'), processors: [{ id: 'rtl', languages: ['systemverilog'], bestSubmissionId: null, bestScore: null, recordScore: null }] };
    vi.spyOn(api, 'allProblems').mockResolvedValue([...tasks, onRtl]);
    const create = vi.spyOn(api, 'createContest').mockResolvedValue(contest());
    open();
    await userEvent.type(await screen.findByRole('textbox', { name: 'Название' }), 'Схемы');
    await userEvent.click(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ }));
    expect(screen.queryByRole('checkbox', { name: /400\. Сумматор/ })).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Процессор' }), 'rtl');

    expect(screen.queryByRole('checkbox', { name: /100\. Сложение векторов/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: /400\. Сумматор/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Создать контест' }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ processor: 'rtl', problemIds: ['chip'] })));
  });

  it.each(['contest', 'pool', 'snapshots'] as const)('сохраняет форму и правки при фоновой ошибке %s и повторяет загрузку рядом с действиями', async source => {
    const original = contest();
    let failed = false;
    const error = new ApiError(503, 'Не удалось обновить данные контеста.');
    vi.spyOn(api, 'getContest').mockImplementation(async () => {
      if (failed && source === 'contest') throw error;
      return original;
    });
    vi.mocked(api.allProblems).mockImplementation(async (query = {}) => {
      if (failed && (source === 'pool' && !query.context || source === 'snapshots' && query.context)) throw error;
      return tasks;
    });
    const client = open('/contest/round/edit');
    const title = await screen.findByRole('textbox', { name: 'Название' });
    await userEvent.clear(title);
    await userEvent.type(title, 'Локальные правки');
    const duration = screen.getByRole('spinbutton', { name: 'Длительность, минут' });
    await userEvent.clear(duration);
    await userEvent.type(duration, '75');
    await userEvent.click(screen.getByRole('checkbox', { name: /300\. Транспонирование/ }));
    await userEvent.type(screen.getByRole('searchbox', { name: 'Найти задачу для контеста' }), 'Редукция');
    const queryKey = source === 'contest' ? ['contest', original.id, creator.id]
      : source === 'pool' ? ['contest-problem-pool', creator.id] : ['contest-edit-tasks', original.id, creator.id];
    failed = true;

    await act(async () => { await client.invalidateQueries({ queryKey, exact: true }); });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(error.message);
    expect(alert.closest('.contest-builder-actions')).toContainElement(screen.getByRole('button', { name: 'Сохранить' }));
    expect(screen.getByRole('textbox', { name: 'Название' })).toBe(title);
    expect(title).toHaveValue('Локальные правки');
    expect(duration).toHaveValue(75);
    expect(screen.getByRole('searchbox', { name: 'Найти задачу для контеста' })).toHaveValue('Редукция');
    expect(screen.getByText('Выбрано: 3')).toBeVisible();
    expect(screen.getByRole('button', { name: `Убрать ${zeta.handle}` })).toBeVisible();
    expect(scrollIntoView).not.toHaveBeenCalled();
    failed = false;
    await userEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(title).toHaveValue('Локальные правки');
    expect(duration).toHaveValue(75);
    expect(screen.getByText('Выбрано: 3')).toBeVisible();
  });

  it('показывает ошибку сохранения у кнопки и заменяет ее актуальной локальной ошибкой', async () => {
    const create = vi.spyOn(api, 'createContest').mockRejectedValue(new ApiError(503, 'Сохранение сейчас недоступно.'));
    open();
    const title = await screen.findByRole('textbox', { name: 'Название' });
    await userEvent.type(title, 'Мой контест');
    await userEvent.click(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ }));
    const button = screen.getByRole('button', { name: 'Создать контест' });
    await userEvent.click(button);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Сохранение сейчас недоступно.');
    expect(alert.closest('.form-feedback')).not.toBeNull();
    expect(alert.closest('.contest-builder-actions')).toContainElement(button);
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    expect(title).toHaveValue('Мой контест');
    expect(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ })).toBeChecked();
    expect(button).toBeEnabled();

    fireEvent.change(screen.getByLabelText('Начало (МСК)'), { target: { value: '2020-01-01T12:00' } });
    await userEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Начало контеста должно быть в будущем.');
    expect(screen.queryByText('Сохранение сейчас недоступно.')).not.toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('показывает локальную проверку формы рядом с кнопкой сохранения', async () => {
    const create = vi.spyOn(api, 'createContest');
    open();
    await userEvent.type(await screen.findByRole('textbox', { name: 'Название' }), 'Будущий контест');
    await userEvent.click(screen.getByRole('checkbox', { name: /100\. Сложение векторов/ }));
    fireEvent.change(screen.getByLabelText('Начало (МСК)'), { target: { value: '2020-01-01T12:00' } });
    const button = screen.getByRole('button', { name: 'Создать контест' });
    await userEvent.click(button);

    const alert = await screen.findByRole('alert');
    expect(alert).toBeVisible();
    expect(alert).toHaveTextContent('Начало контеста должно быть в будущем.');
    expect(alert.closest('.form-feedback')).not.toBeNull();
    expect(alert.closest('.contest-builder-actions')).toContainElement(button);
    expect(screen.getByRole('textbox', { name: 'Название' })).toHaveValue('Будущий контест');
    expect(create).not.toHaveBeenCalled();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(alert);
    await userEvent.click(button);
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
  });
});
