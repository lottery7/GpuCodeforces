import { createContext, useContext, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './api/client';
import type { LoginInput, RegisterInput, User } from './api/types';

type Session = {
  user: User | null;
  isLoading: boolean;
  error: unknown;
  login: (values: LoginInput) => Promise<User>;
  register: (values: RegisterInput) => Promise<User>;
  logout: () => Promise<void>;
  refresh: () => void;
};
const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const cache = useQueryClient();
  const session = useQuery({
    queryKey: ['session'],
    queryFn: async ({ signal }) => {
      try { return await api.session(signal); } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null;
        throw error;
      }
    },
    retry: false,
    staleTime: 30000,
  });
  async function setUser(user: User | null) {
    await cache.cancelQueries();
    cache.removeQueries({ predicate: query => query.queryKey[0] !== 'session' });
    cache.getMutationCache().clear();
    cache.setQueryData(['session'], user);
  }
  async function login(values: LoginInput) { const user = await api.login(values); await setUser(user); return user; }
  async function register(values: RegisterInput) { const user = await api.register(values); await setUser(user); return user; }
  async function logout() {
    try { await api.logout(); } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
    }
    await setUser(null);
  }
  return <SessionContext.Provider value={{ user: session.data ?? null, isLoading: session.isPending, error: session.error, login, register, logout, refresh: () => { void session.refetch(); } }}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error('SessionProvider is required');
  return value;
}
