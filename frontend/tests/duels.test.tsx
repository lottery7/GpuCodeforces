import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { AppNotification, DuelRequest, User } from '../src/api/types';
import Duels from '../src/pages/Duels';
import Notifications from '../src/components/Notifications';
import RuntimeNotice from '../src/components/RuntimeNotice';

const session = vi.hoisted(() => ({ user: null as User | null }));
vi.mock('../src/auth', () => ({ useSession: () => ({ user: session.user, isLoading: false, error: null }) }));
const creator: User = { id: 'creator', handle: 'alex_cuda', rating: null, rank: null, ratingDelta: null, ratingContestId: null };
const opponent: User = { ...creator, id: 'opponent', handle: 'new_cuda' };
const queued: DuelRequest = { id: 'request', mode: 'random', status: 'queued', creator, opponent: null, topics: ['Память'], contestId: null, createdAt: '2026-11-11T10:00:00Z', expiresAt: '2026-11-11T11:00:00Z' };
const invited: DuelRequest = { ...queued, mode: 'invite', status: 'invited', opponent };
const matched: DuelRequest = { ...invited, status: 'matched', contestId: 'room' };
const invitation: AppNotification = { id: 'note', type: 'duel_invitation', message: 'alex_cuda приглашает вас на дуэль.', duelRequestId: invited.id, duelStatus: 'invited', contestId: null, createdAt: queued.createdAt, readAt: null };

function open(path = '/duels/new', cached?: DuelRequest) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  if (cached) client.setQueryData(['current-duel', session.user?.id], cached);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Notifications /><Routes>
    <Route path="/duels/new" element={<Duels />} /><Route path="/contest/:id" element={<p>Комната дуэли</p>} />
    <Route path="/empty" element={<p>Страница</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  session.user = creator;
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  vi.spyOn(api, 'listDuelTopics').mockResolvedValue(['Память', 'Матрицы']);
  vi.spyOn(api, 'getCurrentDuel').mockResolvedValue(null);
  vi.spyOn(api, 'getDuel').mockResolvedValue(queued);
  vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [], total: 0, unreadCount: 0 });
  vi.spyOn(api, 'readNotification').mockResolvedValue(undefined);
});

describe('Подбор дуэли', () => {
  it('отправляет только темы и случайный режим, без выбора задачи и расписания', async () => {
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(queued);
    open();
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Память' }));
    expect(screen.queryByRole('textbox', { name: 'Название' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Начало (МСК)')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Найти задачу для контеста')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Найти соперника' }));
    expect(create).toHaveBeenCalledWith({ mode: 'random', topics: ['Память'] });
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ищем соперника' })).toBeVisible());
    expect(screen.getByRole('button', { name: 'Отменить поиск' })).toBeVisible();
  });

  it('восстанавливает очередь и автоматически открывает подобранную комнату', async () => {
    vi.spyOn(api, 'getCurrentDuel').mockResolvedValue(queued);
    const get = vi.spyOn(api, 'getDuel').mockResolvedValue(queued);
    const cache = open();
    expect(await screen.findByText('Ищем соперника')).toBeVisible();
    get.mockResolvedValue(matched);
    await act(async () => { await cache.invalidateQueries({ queryKey: ['duel-request'] }); });
    expect(await screen.findByText('Комната дуэли')).toBeVisible();
  });

  it('посылает приглашение по нику и позволяет отменить его', async () => {
    const create = vi.spyOn(api, 'createDuel').mockResolvedValue(invited);
    vi.spyOn(api, 'getDuel').mockResolvedValue(invited);
    const cancel = vi.spyOn(api, 'cancelDuel').mockResolvedValue({ ...invited, status: 'cancelled' });
    open();
    await userEvent.click(await screen.findByRole('radio', { name: 'Пригласить по нику' }));
    await userEvent.type(screen.getByRole('textbox', { name: 'Ник соперника' }), ` ${opponent.handle} `);
    await userEvent.click(screen.getByRole('button', { name: 'Отправить приглашение' }));
    expect(create).toHaveBeenCalledWith({ mode: 'invite', topics: [], opponentHandle: opponent.handle });
    await waitFor(() => expect(screen.getByText('Ожидаем ответ на приглашение')).toBeVisible());
    await userEvent.click(screen.getByRole('button', { name: 'Отменить приглашение' }));
    expect(cancel).toHaveBeenCalledWith(invited.id);
    expect(await screen.findByText('Поиск или приглашение отменены')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
  });

  it('оставляет форму доступной после ошибки подбора', async () => {
    vi.spyOn(api, 'createDuel').mockRejectedValue(new Error('По выбранным темам нет задач.'));
    open();
    await userEvent.click(await screen.findByRole('button', { name: 'Найти соперника' }));
    expect(await screen.findByText('По выбранным темам нет задач.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Найти соперника' })).toBeEnabled();
  });

  it('не открывает закешированный матч до свежего ответа сервера', async () => {
    let finish!: (value: DuelRequest | null) => void;
    vi.spyOn(api, 'getCurrentDuel').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    open('/duels/new', { ...matched, expiresAt: new Date(Date.now() + 60000).toISOString() });
    expect(screen.queryByText('Комната дуэли')).not.toBeInTheDocument();
    expect(screen.getByText('Загрузка дуэли...')).toBeVisible();
    await act(async () => finish(null));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
    expect(screen.queryByText('Комната дуэли')).not.toBeInTheDocument();
  });

  it('не перенаправляет в уже закончившуюся дуэль из текущего запроса', async () => {
    vi.spyOn(api, 'getCurrentDuel').mockResolvedValue({ ...matched, expiresAt: new Date(Date.now() - 1000).toISOString() });
    open();
    expect(await screen.findByText('Дуэль завершена')).toBeVisible();
    expect(screen.queryByText('Комната дуэли')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Новая дуэль' }));
    expect(await screen.findByRole('button', { name: 'Найти соперника' })).toBeVisible();
  });
});

describe('Уведомления', () => {
  beforeEach(() => { session.user = opponent; });

  it('показывает непрочитанное приглашение, отмечает прочитанным и принимает дуэль', async () => {
    vi.spyOn(api, 'listNotifications').mockResolvedValue({ items: [invitation], total: 1, unreadCount: 1 });
    vi.spyOn(api, 'getDuel').mockResolvedValue(invited);
    const accept = vi.spyOn(api, 'acceptDuel').mockResolvedValue(matched);
    open('/empty');
    await userEvent.click(await screen.findByRole('button', { name: 'Уведомления: 1 непрочитанных' }));
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
    expect(await screen.findByText('Новых уведомлений нет.')).toBeVisible();
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
