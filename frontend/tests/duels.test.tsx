import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { AppNotification, Contest, DuelProblem, DuelRequest, Processor, Standing, User } from '../src/api/types';
import Duels from '../src/pages/Duels';
import NewDuel from '../src/pages/NewDuel';
import DuelBoard from '../src/components/DuelBoard';
import Notifications from '../src/components/Notifications';
import RuntimeNotice from '../src/components/RuntimeNotice';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
const creator: User = { id: 'creator', handle: 'alex_cuda', ratings: {} };
const opponent: User = { ...creator, id: 'opponent', handle: 'new_cuda' };
const expiresAt = new Date(Date.now() + 3_600_000).toISOString();
const queued: DuelRequest = { id: 'request', mode: 'random', processor: 'gpu-cuda', status: 'queued', creator, opponent: null, contestId: null, createdAt: '2026-11-11T10:00:00Z', expiresAt, problemId: null, problemTitle: null, difficulty: null, startAt: null, surrenderedUserId: null };
const invited: DuelRequest = { ...queued, mode: 'invite', status: 'invited', opponent };
const matched: DuelRequest = { ...invited, status: 'matched', contestId: 'room', problemId: 'vector', problemTitle: 'Сумма векторов', difficulty: 'easy' };
const invitation: AppNotification = { id: 'note', type: 'duel_invitation', message: 'alex_cuda приглашает вас на дуэль.', duelRequestId: invited.id, duelStatus: 'invited', contestId: null, createdAt: queued.createdAt, readAt: null };
const problem: DuelProblem = { id: 'vector', code: 'A', title: 'Сумма векторов', difficulty: 'easy' };

function open(path = '/duels/new') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Notifications /><Routes>
    <Route path="/duels/new" element={<NewDuel />} /><Route path="/duels" element={<Duels />} /><Route path="/contest/:id" element={<p>Комната дуэли</p>} />
    <Route path="/empty" element={<p>Страница</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  session.user = creator;
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  vi.spyOn(api, 'listDuelProblems').mockResolvedValue({ items: [problem], total: 1 });
  vi.spyOn(api, 'listUsers').mockResolvedValue({ items: [creator, opponent], total: 2 });
  vi.spyOn(api, 'listDuels').mockResolvedValue({ items: [], total: 0 });
  vi.spyOn(api, 'getCurrentDuel').mockResolvedValue(null);
  vi.spyOn(api, 'getDuel').mockResolvedValue(queued);
  vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [], total: 0, unreadCount: 0 });
  vi.spyOn(api, 'readNotification').mockResolvedValue(undefined);
});

describe('Подбор дуэли', () => {
  it('создает случайную дуэль без выбора задачи и расписания', async () => {
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(queued);
    open();
    expect(screen.queryByRole('textbox', { name: 'Название' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Начало (МСК)')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Найти задачу для контеста')).not.toBeInTheDocument();
    expect(screen.queryByRole('searchbox', { name: 'Найти задачу' })).not.toBeInTheDocument();
    expect(api.listDuelProblems).not.toHaveBeenCalled();
    expect(screen.getByText('Длительность: от 1 до 3 часов.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Найти соперника' }));
    expect(create).toHaveBeenCalledWith({ mode: 'random', processor: 'gpu-cuda' });
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Поиск соперника' })).toBeVisible());
    expect(screen.getByRole('button', { name: 'Отменить поиск' })).toBeVisible();
  });

  it('дожидается каталога и выбирает единственный доступный RTL для новой дуэли', async () => {
    let finish!: (value: Processor[]) => void;
    vi.mocked(api.listProcessors).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue({ ...queued, processor: 'rtl' });
    open();
    const submit = screen.getByRole('button', { name: 'Найти соперника' });
    expect(submit).toBeDisabled();
    await userEvent.click(submit);
    expect(create).not.toHaveBeenCalled();
    await act(async () => finish([{ id: 'rtl', title: 'RTL', model: 'Verilator', languages: [{ id: 'systemverilog', title: 'SystemVerilog', extension: '.sv' }], scoreType: 'CYCLES_X_DEPTH_X_TRANSISTORS', emulated: true }]));
    await waitFor(() => expect(submit).toBeEnabled());
    expect(screen.queryByRole('combobox', { name: 'Процессор дуэли' })).not.toBeInTheDocument();
    await userEvent.click(submit);
    expect(create).toHaveBeenCalledWith({ mode: 'random', processor: 'rtl' });
  });

  it('восстанавливает очередь и автоматически открывает подобранную комнату', async () => {
    const get = vi.spyOn(api, 'getDuel').mockResolvedValue(queued);
    const cache = open('/duels/new?request=request');
    expect(await screen.findByText('Поиск соперника')).toBeVisible();
    get.mockResolvedValue(matched);
    await act(async () => { await cache.invalidateQueries({ queryKey: ['duel-request'] }); });
    expect(await screen.findByText('Комната дуэли')).toBeVisible();
  });

  it('подбирает ник клавиатурой, выбирает задачу и отменяет приглашение', async () => {
    vi.mocked(api.listDuelProblems).mockResolvedValue({ items: [problem, { ...problem, id: 'medium', code: '002', difficulty: 'medium', title: 'Матрица' }], total: 2 });
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    vi.spyOn(api, 'getDuel').mockResolvedValue(invited);
    const cancel = vi.spyOn(api, 'cancelDuel').mockResolvedValue({ ...invited, status: 'cancelled' });
    open();
    await userEvent.click(await screen.findByRole('radio', { name: 'Выбрать соперника' }));
    const input = screen.getByRole('combobox', { name: 'Ник соперника' });
    await userEvent.type(input, 'cuda');
    const options = await screen.findByRole('listbox', { name: 'Участники' });
    expect(within(options).queryByRole('option', { name: creator.handle })).not.toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(input).toHaveValue(opponent.handle);
    expect(screen.getByRole('radio', { name: 'Случайная задача' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать задачу' }));
    await userEvent.click(await screen.findByRole('button', { name: '002. Матрица' }));
    expect(screen.queryByRole('searchbox', { name: 'Найти задачу' })).not.toBeInTheDocument();
    expect(screen.getByText('Длительность: 2 часа.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Пригласить на дуэль' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', processor: 'gpu-cuda', opponentHandle: opponent.handle, problemId: 'medium' });
    await waitFor(() => expect(screen.getByText('Приглашение отправлено')).toBeVisible());
    await userEvent.click(screen.getByRole('button', { name: 'Отменить приглашение' }));
    expect(cancel).toHaveBeenCalledWith(invited.id);
    expect(await screen.findByText('Приглашение отменено')).toBeVisible();
    await userEvent.click(screen.getByRole('link', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
  });

  it('оставляет форму доступной после ошибки подбора', async () => {
    vi.spyOn(api, 'createDuel').mockRejectedValue(new Error('Нет доступных задач для дуэли.'));
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Найти соперника' }));
    expect(await screen.findByText('Нет доступных задач для дуэли.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Найти соперника' })).toBeEnabled();
  });

  it('закрывает подсказки по Escape и выбирает участника кликом', async () => {
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    const input = screen.getByRole('combobox', { name: 'Ник соперника' });
    await userEvent.type(input, 'cuda');
    await screen.findByRole('option', { name: opponent.handle });
    await userEvent.keyboard('{Escape}');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}');
    await userEvent.click(screen.getByRole('option', { name: opponent.handle }));
    expect(input).toHaveValue(opponent.handle);
    expect(input).toHaveAttribute('aria-expanded', 'false');
  });

  it('приглашает выбранного соперника со случайной задачей без загрузки банка', async () => {
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    vi.mocked(api.getDuel).mockResolvedValue(invited);
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    expect(screen.getByRole('group', { name: 'Соперник' })).toBeVisible();
    expect(screen.getByRole('radio', { name: 'Случайная задача' })).toBeChecked();
    await userEvent.type(screen.getByRole('combobox', { name: 'Ник соперника' }), opponent.handle);
    await userEvent.click(screen.getByRole('button', { name: 'Пригласить на дуэль' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', processor: 'gpu-cuda', opponentHandle: opponent.handle });
    expect(api.listDuelProblems).not.toHaveBeenCalled();
  });

  it('сохраняет выбранную задачу при поиске, фильтрации и смене страницы', async () => {
    const medium = { ...problem, id: 'medium', code: '002', title: 'Матрица', difficulty: 'medium' as const };
    const list = vi.mocked(api.listDuelProblems).mockImplementation(async query => ({ items: query?.q || query?.difficulty || query?.offset ? [medium] : [problem, medium], total: 21 }));
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    vi.mocked(api.getDuel).mockResolvedValue(invited);
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    await userEvent.type(screen.getByRole('combobox', { name: 'Ник соперника' }), opponent.handle);
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать задачу' }));
    await userEvent.click(await screen.findByRole('button', { name: 'A. Сумма векторов' }));
    expect(screen.queryByRole('searchbox', { name: 'Найти задачу' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Изменить задачу' }));
    const search = screen.getByRole('searchbox', { name: 'Найти задачу' });
    expect(search).toHaveAttribute('placeholder', 'Название или номер задачи');
    await userEvent.type(search, 'Матри');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Матри', offset: 0, limit: 10 }), expect.any(AbortSignal)));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Сложность задачи' }), 'medium');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Матри', difficulty: 'medium', offset: 0, limit: 10 }), expect.any(AbortSignal)));
    await userEvent.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Матри', difficulty: 'medium', offset: 10, limit: 10 }), expect.any(AbortSignal)));
    await userEvent.click(screen.getByRole('button', { name: 'Оставить выбранную задачу' }));
    expect(screen.getByText('A. Сумма векторов')).toBeVisible();
    expect(screen.getByText('Длительность: 1 час.')).toBeVisible();
    expect(screen.queryByRole('searchbox', { name: 'Найти задачу' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Пригласить на дуэль' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', processor: 'gpu-cuda', opponentHandle: opponent.handle, problemId: problem.id });
  });

  it('возврат к случайной задаче убирает прежний выбор из приглашения', async () => {
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    vi.mocked(api.getDuel).mockResolvedValue(invited);
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    await userEvent.type(screen.getByRole('combobox', { name: 'Ник соперника' }), opponent.handle);
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать задачу' }));
    await userEvent.click(await screen.findByRole('button', { name: 'A. Сумма векторов' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Случайная задача' }));
    expect(screen.getByText('Длительность: от 1 до 3 часов.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Пригласить на дуэль' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', processor: 'gpu-cuda', opponentHandle: opponent.handle });
  });

  it('повторяет поиск после ошибки и не отправляет приглашение со старой выбранной задачей', async () => {
    const medium = { ...problem, id: 'medium', code: '002', title: 'Матрица', difficulty: 'medium' as const };
    const list = vi.mocked(api.listDuelProblems).mockImplementation(async query => {
      if (query?.q) throw new Error('Не удалось найти задачи.');
      return { items: [problem], total: 1 };
    });
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    await userEvent.type(screen.getByRole('combobox', { name: 'Ник соперника' }), opponent.handle);
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать задачу' }));
    await userEvent.click(await screen.findByRole('button', { name: 'A. Сумма векторов' }));
    await userEvent.click(screen.getByRole('button', { name: 'Изменить задачу' }));
    await userEvent.type(screen.getByRole('searchbox', { name: 'Найти задачу' }), 'Матрица');
    expect(await screen.findByText('Не удалось найти задачи.')).toBeVisible();
    expect(screen.getByText('A. Сумма векторов')).toBeVisible();
    const retries = screen.getAllByRole('button', { name: /Повторить/ });
    expect(retries).toHaveLength(1);
    expect(retries[0]).toHaveAccessibleName('Повторить поиск');
    list.mockResolvedValue({ items: [medium], total: 1 });
    await userEvent.click(retries[0]);
    expect(await screen.findByRole('button', { name: '002. Матрица' })).toBeVisible();
    expect(create).not.toHaveBeenCalled();
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Матрица', offset: 0, limit: 10 }), expect.any(AbortSignal));
    await userEvent.click(screen.getByRole('button', { name: '002. Матрица' }));
    expect(screen.getByText('002. Матрица')).toBeVisible();
    expect(screen.getByText('Длительность: 2 часа.')).toBeVisible();
  });

  it('сбрасывает выбранную задачу при смене процессора и ищет в новом пуле', async () => {
    const rtl = { ...problem, id: 'rtl-vector', code: '002', title: 'Сумма на RTL' };
    const list = vi.mocked(api.listDuelProblems).mockImplementation(async query => ({ items: [query?.processor === 'rtl' ? rtl : problem], total: 1 }));
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    open();
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать соперника' }));
    await userEvent.type(screen.getByRole('combobox', { name: 'Ник соперника' }), opponent.handle);
    await userEvent.click(screen.getByRole('radio', { name: 'Выбрать задачу' }));
    await userEvent.click(await screen.findByRole('button', { name: 'A. Сумма векторов' }));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Процессор дуэли' }), 'rtl');
    expect(screen.queryByText('A. Сумма векторов')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Пригласить на дуэль' })).toBeDisabled();
    await userEvent.click(await screen.findByRole('button', { name: '002. Сумма на RTL' }));
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ processor: 'rtl', offset: 0, limit: 10 }), expect.any(AbortSignal));
    await userEvent.click(screen.getByRole('button', { name: 'Пригласить на дуэль' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', processor: 'rtl', opponentHandle: opponent.handle, problemId: rtl.id });
  });

  it('показывает несколько дуэлей списком без автоматического перехода и не блокирует новую', async () => {
    vi.mocked(api.listDuels).mockResolvedValue({ items: [matched, { ...matched, id: 'second', contestId: 'room2' }, invited], total: 3 });
    open('/duels');
    const table = await screen.findByRole('table', { name: 'Мои дуэли' });
    expect(within(table).getAllByRole('link', { name: 'Открыть дуэль' })).toHaveLength(2);
    expect(screen.queryByText('Комната дуэли')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('link', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
    expect(api.getCurrentDuel).not.toHaveBeenCalled();
  });

  it('не перенаправляет в уже закончившуюся дуэль из текущего запроса', async () => {
    vi.mocked(api.getDuel).mockResolvedValue({ ...matched, expiresAt: new Date(Date.now() - 1000).toISOString() });
    open('/duels/new?request=request');
    expect(await screen.findByText('Дуэль завершена')).toBeVisible();
    expect(screen.queryByText('Комната дуэли')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('link', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
  });

  it('переходит по страницам истории и меняет фильтр при открытии текущих дуэлей', async () => {
    const list = vi.mocked(api.listDuels).mockImplementation(async query => ({ items: [{ ...matched, id: String(query?.offset), surrenderedUserId: creator.id }], total: 21 }));
    open('/duels?tab=history');
    expect(await screen.findByText('Завершена сдачей')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(list).toHaveBeenCalledWith({ phase: 'finished', offset: 20, limit: 20 }, expect.any(AbortSignal)));
    await userEvent.click(screen.getByRole('link', { name: 'Текущие' }));
    await waitFor(() => expect(list).toHaveBeenCalledWith({ phase: 'active', offset: 0, limit: 20 }, expect.any(AbortSignal)));
  });

  it('сохраняет процессор при переходе между страницами и вкладками списка', async () => {
    const list = vi.mocked(api.listDuels).mockResolvedValue({ items: [{ ...matched, processor: 'rtl' }], total: 21 });
    open('/duels?processor=rtl&tab=history');
    await screen.findByRole('table', { name: 'Мои дуэли' });
    await userEvent.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ processor: 'rtl', phase: 'finished', offset: 20, limit: 20 }, expect.any(AbortSignal)));
    await userEvent.click(screen.getByRole('link', { name: 'Текущие' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ processor: 'rtl', phase: 'active', offset: 0, limit: 20 }, expect.any(AbortSignal)));
  });
});

describe('Сдача дуэли', () => {
  const room: Contest = { id: 'room', title: 'Дуэль', format: 'duel', startAt: new Date(Date.now() - 60000).toISOString(), endAt: new Date(Date.now() + 3600000).toISOString(), authors: [creator], problemIds: ['vector'], problemCount: 1, registered: true, challengerId: creator.id, opponentId: opponent.id, processor: 'gpu-cuda', rulesMarkdown: '' };

  function board(contest = room) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><DuelBoard contest={contest} finished={false} /></MemoryRouter></QueryClientProvider>);
    return client;
  }

  it('подтверждает сдачу на странице и обновляет комнату, список и результаты', async () => {
    vi.spyOn(api, 'contestStandings').mockResolvedValueOnce([{ rank: 1, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.05, type: 'TIME_MS' } }, { rank: 2, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }]).mockResolvedValue([{ rank: 1, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }, { rank: 2, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.05, type: 'TIME_MS' }, surrendered: true }]);
    const surrender = vi.spyOn(api, 'surrenderContest').mockResolvedValue(undefined);
    const client = board();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await userEvent.click(await screen.findByRole('button', { name: 'Сдаться' }));
    expect(surrender).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(screen.queryByRole('group', { name: 'Подтвердить сдачу' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Сдаться' }));
    await userEvent.click(screen.getByRole('button', { name: 'Да, сдаться' }));
    await waitFor(() => expect(surrender).toHaveBeenCalledWith(room.id));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['contest', room.id] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['standings', room.id] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['duels'] });
    expect(await screen.findByText(/Победитель:/)).toHaveTextContent(opponent.handle);
    expect(screen.queryByRole('button', { name: 'Сдаться' })).not.toBeInTheDocument();
  });

  it('присуждает победу без принятого решения после сдачи соперника', async () => {
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }, { rank: 2, user: creator, solved: 0, totalSeconds: 0, times: {}, bestScore: null, surrendered: true }]);
    board({ ...room, surrenderedUserId: creator.id });
    expect(await screen.findByText(/Победитель:/)).toHaveTextContent(opponent.handle);
    expect(screen.getByText('Сдался')).toBeVisible();
    expect(screen.queryByText('Ничья.')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Сдаться' })).not.toBeInTheDocument();
  });

  it('продолжает ждать проверки после таймера и не объявляет предварительный результат', async () => {
    vi.useFakeTimers();
    try {
      const rows = vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }, { rank: 1, user: creator, solved: 0, totalSeconds: 0, times: {}, bestScore: null }]);
      board({ ...room, endAt: new Date(Date.now() - 1000).toISOString(), resultsPending: true });
      await act(async () => { await vi.advanceTimersByTimeAsync(1); });
      expect(screen.getByText('Проверяются посылки')).toBeVisible();
      expect(rows).toHaveBeenCalledTimes(1);
      expect(screen.queryByText('Ничья.')).not.toBeInTheDocument();
      expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(rows).toHaveBeenCalledTimes(2);
    } finally { vi.useRealTimers(); }
  });

  it('получает финальную таблицу после последнего вердикта до объявления победителя', async () => {
    let finish!: (value: Standing[]) => void;
    const rows = vi.spyOn(api, 'contestStandings').mockResolvedValueOnce([{ rank: 1, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.1, type: 'TIME_MS' } }, { rank: 2, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }]).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const endAt = new Date(Date.now() - 1000).toISOString();
    const renderBoard = (pending: boolean) => <QueryClientProvider client={client}><MemoryRouter><DuelBoard contest={{ ...room, endAt, resultsPending: pending }} contestUpdatedAt={Date.now()} finished /></MemoryRouter></QueryClientProvider>;
    const view = render(renderBoard(true));
    expect(await screen.findByText('0.1')).toBeVisible();
    view.rerender(renderBoard(false));
    await waitFor(() => expect(rows).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
    expect(screen.queryByText('Ничья.')).not.toBeInTheDocument();
    await act(async () => finish([{ rank: 1, user: opponent, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.05, type: 'TIME_MS' } }, { rank: 2, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.1, type: 'TIME_MS' } }]));
    expect(await screen.findByText(/Победитель:/)).toHaveTextContent(opponent.handle);
  });

  it('не завершает дуэль по кешу комнаты, полученному до дедлайна', async () => {
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 0.1, type: 'TIME_MS' } }, { rank: 2, user: opponent, solved: 0, totalSeconds: 0, times: {}, bestScore: null }]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><DuelBoard contest={{ ...room, endAt: new Date(Date.now() - 1000).toISOString(), resultsPending: false }} contestUpdatedAt={Date.now() - 5000} finished /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('0.1')).toBeVisible();
    expect(screen.queryByText(/Победитель:/)).not.toBeInTheDocument();
    expect(screen.getByText('Загрузка результатов...')).toBeVisible();
  });

  it('показывает результат эмулятора в его единицах без миллисекунд', async () => {
    vi.spyOn(api, 'contestStandings').mockResolvedValue([{ rank: 1, user: creator, solved: 1, totalSeconds: 0, times: {}, bestScore: { value: 4096, type: 'CYCLES_X_DEPTH_X_TRANSISTORS' } }]);
    board({ ...room, processor: 'rtl' });
    expect(await screen.findByText(/такты × глубина × транзисторы/)).toBeVisible();
    expect(screen.queryByText(/мс/)).not.toBeInTheDocument();
  });
});

describe('Уведомления', () => {
  beforeEach(() => { session.user = opponent; });

  it('показывает непрочитанное приглашение, отмечает прочитанным и принимает дуэль', async () => {
    vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [invitation], total: 1, unreadCount: 1 });
    vi.spyOn(api, 'getDuel').mockResolvedValue(invited);
    const accept = vi.spyOn(api, 'acceptDuel').mockResolvedValue(matched);
    open('/empty');
    const toggle = await screen.findByRole('button', { name: 'Уведомления: 1 непрочитанных' });
    expect(toggle).toHaveAttribute('title', 'Уведомления');
    expect(toggle).toHaveTextContent(/^1$/);
    await userEvent.click(toggle);
    const panel = screen.getByRole('dialog', { name: 'Уведомления' });
    await userEvent.click(await within(panel).findByRole('button', { name: 'Принять дуэль' }));
    expect(accept).toHaveBeenCalledWith(invited.id);
    expect(api.readNotification).toHaveBeenCalledWith(invitation.id);
    expect(api.getDuel).not.toHaveBeenCalled();
    expect(await screen.findByText('Комната дуэли')).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('отклоняет приглашение и убирает кнопки принятия', async () => {
    vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [invitation], total: 1, unreadCount: 1 });
    vi.spyOn(api, 'getDuel').mockResolvedValue(invited);
    const decline = vi.spyOn(api, 'declineDuel').mockResolvedValue({ ...invited, status: 'declined' });
    open('/empty');
    await userEvent.click(await screen.findByRole('button', { name: /Уведомления/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Отклонить' }));
    expect(decline).toHaveBeenCalledWith(invited.id);
    expect(await screen.findByText('Приглашение отклонено.')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Принять дуэль' })).not.toBeInTheDocument();
  });

  it('показывает отмененное приглашение и закрывается по Escape с возвратом фокуса', async () => {
    vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [{ ...invitation, duelStatus: 'cancelled' }], total: 1, unreadCount: 0 });
    open('/empty');
    const toggle = await screen.findByRole('button', { name: 'Уведомления' });
    await userEvent.click(toggle);
    expect(await screen.findByText('Приглашение отменено.')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Принять дуэль' })).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(toggle).toHaveFocus();
  });

  it('показывает пустой список и ошибку обновления', async () => {
    const list = vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [], total: 0, unreadCount: 0 });
    const cache = open('/empty');
    await userEvent.click(await screen.findByRole('button', { name: 'Уведомления' }));
    expect(await screen.findByText('Уведомлений пока нет.')).toBeVisible();
    list.mockRejectedValue(new Error('Нет связи.'));
    await act(async () => { await cache.invalidateQueries({ queryKey: ['notifications'] }); });
    expect(await screen.findByText('Нет связи.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Повторить загрузку' })).toBeVisible();
  });

  it('загружает старое приглашение следующей страницей без запросов на каждую карточку', async () => {
    const notes: AppNotification[] = [
      ...Array.from({ length: 30 }, (_, index) => ({ ...invitation, id: `old-${index}`, message: `Уведомление ${index}`, type: 'duel_declined' as const, duelStatus: 'declined' as const, readAt: invitation.createdAt })),
      { ...invitation, id: 'older-invite', message: 'Старое непрочитанное приглашение.' },
    ];
    const list = vi.spyOn(api, 'listNotifications').mockImplementation(async (query = {}) => ({ items: notes.slice(query.offset ?? 0, (query.offset ?? 0) + (query.limit ?? 30)), total: notes.length, unreadCount: 1 }));
    open('/empty');
    await userEvent.click(await screen.findByRole('button', { name: 'Уведомления: 1 непрочитанных' }));
    expect(screen.queryByText('Старое непрочитанное приглашение.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Загрузить еще' }));
    expect(await screen.findByText('Старое непрочитанное приглашение.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Принять дуэль' })).toBeVisible();
    expect(list).toHaveBeenCalledWith({ offset: 30, limit: 30 }, expect.any(AbortSignal));
    expect(api.getDuel).not.toHaveBeenCalled();
    expect(api.readNotification).toHaveBeenCalledWith('older-invite');
  });
});

describe('Режим стенда', () => {
  it('один раз сообщает о тестовых результатах локального стенда', async () => {
    vi.spyOn(api, 'getRuntime').mockResolvedValue({ executionMode: 'mock', gpuName: null });
    const client = new QueryClient();
    render(<QueryClientProvider client={client}><RuntimeNotice /></QueryClientProvider>);
    expect(await screen.findByText('Локальный стенд: код не выполняется на GPU, результаты тестовые.')).toBeVisible();
  });
});
