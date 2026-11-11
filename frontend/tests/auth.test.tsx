import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../src/api/client';
import type { User } from '../src/api/types';
import { SessionProvider, useSession } from '../src/auth';

const user: User = { id: 'cuda-user', handle: 'cuda_user', ratings: {} };
const loginInput = { identifier: user.handle, password: 'password123' };
const registerInput = { handle: user.handle, email: 'cuda@example.com', password: 'password123' };
const privateKey = ['submission', '42', 'previous-user'];

function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const hook = renderHook(useSession, { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}><SessionProvider>{children}</SessionProvider></QueryClientProvider> });
  return { ...hook, client };
}

describe('Сессия', () => {
  it.each(['login', 'register'] as const)('%s обновляет пользователя, удаляет приватный кеш и сохраняет подписку на сессию', async action => {
    const session = vi.spyOn(api, 'session').mockRejectedValue(new ApiError(401, 'Войдите.'));
    const request = vi.spyOn(api, action).mockResolvedValue(user);
    const { result, client } = open();
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.user).toBeNull();
    const query = client.getQueryCache().find({ queryKey: ['session'], exact: true });
    client.setQueryData(privateKey, { code: 'private source' });

    await act(async () => { expect(await (action === 'login' ? result.current.login(loginInput) : result.current.register(registerInput))).toEqual(user); });

    expect(request).toHaveBeenCalledWith(action === 'login' ? loginInput : registerInput);
    await waitFor(() => expect(result.current.user).toEqual(user));
    expect(client.getQueryData(privateKey)).toBeUndefined();
    expect(client.getQueryCache().find({ queryKey: ['session'], exact: true })).toBe(query);
    expect(query?.getObserversCount()).toBe(1);

    const updated = { ...user, handle: 'updated_handle' };
    session.mockResolvedValue(updated);
    act(() => result.current.refresh());
    await waitFor(() => expect(result.current.user).toEqual(updated));
    expect(session).toHaveBeenCalledTimes(2);
  });

  it.each([204, 401])('выход при ответе %s очищает сессию и приватный кеш без потери подписки', async status => {
    vi.spyOn(api, 'session').mockResolvedValue(user);
    const logout = vi.spyOn(api, 'logout').mockResolvedValue(undefined);
    if (status === 401) logout.mockRejectedValue(new ApiError(401, 'Сессия истекла.'));
    const { result, client } = open();
    await waitFor(() => expect(result.current.user).toEqual(user));
    const query = client.getQueryCache().find({ queryKey: ['session'], exact: true });
    client.setQueryData(privateKey, { code: 'private source' });

    await act(async () => { await result.current.logout(); });

    expect(logout).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(result.current.user).toBeNull());
    expect(client.getQueryData(privateKey)).toBeUndefined();
    expect(client.getQueryCache().find({ queryKey: ['session'], exact: true })).toBe(query);
    expect(query?.getObserversCount()).toBe(1);
  });

  it('сохраняет пользователя и кеш, если сервер не подтвердил выход', async () => {
    vi.spyOn(api, 'session').mockResolvedValue(user);
    vi.spyOn(api, 'logout').mockRejectedValue(new ApiError(500, 'Не удалось выйти.'));
    const { result, client } = open();
    await waitFor(() => expect(result.current.user).toEqual(user));
    client.setQueryData(privateKey, { code: 'private source' });

    await act(async () => { await expect(result.current.logout()).rejects.toThrow('Не удалось выйти.'); });

    expect(result.current.user).toEqual(user);
    expect(client.getQueryData(privateKey)).toEqual({ code: 'private source' });
    expect(client.getQueryData(['session'])).toEqual(user);
  });
});
