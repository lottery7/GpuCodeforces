import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import Virtual from '../src/pages/Virtual';

vi.mock('../src/auth', () => ({ useSession: () => ({ user: { id: 'owner' }, isLoading: false }) }));

it('использует серверное время и ждёт позднего вердикта после дедлайна', async () => {
  const now = Date.now();
  vi.spyOn(api, 'getVirtual').mockResolvedValue({ id: 'attempt', contestId: 'round', startedAt: new Date(now - 3600000).toISOString(), endsAt: new Date(now - 1000).toISOString(), serverNow: new Date(now).toISOString(), pending: true, solved: 0, totalSeconds: 0, times: {} });
  vi.spyOn(api, 'allProblems').mockResolvedValue([]);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/virtual/attempt']}><Routes><Route path="/virtual/:id" element={<Virtual />} /></Routes></MemoryRouter></QueryClientProvider>);
  expect(await screen.findByText('Время истекло. Ожидаем проверки отправленных решений.')).toBeVisible();
  expect(screen.getByRole('link', { name: 'Дорешивание' })).toHaveAttribute('href', '/contest/round?mode=upsolving');
});
