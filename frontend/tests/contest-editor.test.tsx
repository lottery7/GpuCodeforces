import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest, Problem, User } from '../src/api/types';
import ContestEditor from '../src/pages/ContestEditor';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));

function user(id: string, handle: string): User {
  return { id, handle, rating: null, rank: null, ratingDelta: null, ratingContestId: null };
}

function problem(id: string, code: string, title: string): Problem {
  return { id, code, title, difficulty: 'easy', tags: ['Массивы'], solved: false, solvedCount: 0, lastSubmissionId: null, lastStatus: null, bestSubmissionId: null, bestTimeMs: null, recordTimeMs: null };
}

const creator = user('creator', 'creator_cuda');
const zeta = user('zeta', 'zeta_cuda');
const alpha = user('alpha', 'alpha_cuda');
const tasks = [problem('add', '100', 'Сложение векторов'), problem('reduce', '200', 'Редукция'), problem('transpose', '300', 'Транспонирование')];

function futureStart(days = 1) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 16) + ':00.000Z';
}

function contest(): Contest {
  const startAt = futureStart();
  return { id: 'round', title: 'Исходный контест', startAt, endAt: new Date(Date.parse(startAt) + 120 * 60000).toISOString(), authors: [creator, zeta], problemIds: ['add', 'reduce'], problemCount: 2, registered: false, gpu: 'NVIDIA A100' };
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
  vi.spyOn(api, 'allProblems').mockResolvedValue(tasks);
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
      title: 'GPU Round', startAt, endAt: new Date(Date.parse(startAt) + 90 * 60000).toISOString(),
      authorIds: [zeta.id, alpha.id], problemIds: ['transpose', 'reduce', 'add'], hackDurationMinutes: 0,
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
      title: 'Мои изменения', startAt: original.startAt, endAt: new Date(Date.parse(original.startAt) + 75 * 60000).toISOString(),
      authorIds: [creator.id, alpha.id], problemIds: ['reduce', 'transpose'], hackDurationMinutes: 0,
    }));
  });
});
