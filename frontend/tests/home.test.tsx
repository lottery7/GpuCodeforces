import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Contest, Processor, Submission, User } from '../src/api/types';
import Home from '../src/pages/Home';

vi.mock('../src/auth', () => ({ useSession: () => ({ user: { id: 'own', handle: 'own_cuda' }, isLoading: false }) }));

const processors = [
  { id: 'gpu-cuda', title: 'GPU (CUDA)', model: 'RTX 3060', languages: [{ id: 'cuda', title: 'CUDA C++', extension: '.cu' }], scoreType: 'TIME_MS', emulated: false },
  { id: 'rtl', title: 'Своё железо (Verilog)', model: 'Verilator', languages: [{ id: 'verilog', title: 'Verilog', extension: '.v' }], scoreType: 'CYCLES_X_DEPTH_X_TRANSISTORS', emulated: true },
] as Processor[];

function person(id: string, ratings: User['ratings']): User {
  return { id, handle: id, ratings };
}

const rated = (rating: number, rank: number) => ({ rating, rank, ratingDelta: null, ratingContestId: null });

it('собирает ленту всех участников, ближайший контест и рейтинг выбранного процессора', async () => {
  const stranger = person('stranger', {});
  vi.spyOn(api, 'listProcessors').mockResolvedValue(processors);
  vi.spyOn(api, 'listLatestSubmissions').mockResolvedValue({ items: [{
    id: 'aaaaaaaa-0000-0000-0000-000000000001', problemId: 'adder', problemTitle: 'Сумматор', user: stranger, contestId: null, contestTitle: null,
    createdAt: '2026-11-11T10:00:00Z', status: 'accepted', processor: 'rtl', language: 'verilog', score: { value: 4096, type: 'CYCLES_X_DEPTH_X_TRANSISTORS' }, comparable: true,
  } as Submission] });
  vi.spyOn(api, 'listContests').mockResolvedValue([{
    id: 'round', title: 'Раунд на Verilog', processor: 'rtl', format: 'standard', startAt: new Date(Date.now() + 3600000).toISOString(),
    endAt: new Date(Date.now() + 9000000).toISOString(), problemCount: 3, registered: false, authors: [],
  } as unknown as Contest]);
  const listUsers = vi.spyOn(api, 'listUsers').mockImplementation(query => Promise.resolve(query?.processor === 'rtl'
    ? { items: [person('own', { rtl: rated(1500, 1) })], total: 1 }
    : { items: [person('leader', { 'gpu-cuda': rated(2100, 1) }), person('own', { 'gpu-cuda': rated(1400, 2) })], total: 2 }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={client}><MemoryRouter><Home /></MemoryRouter></QueryClientProvider>);

  const feed = await screen.findByRole('table', { name: 'История посылок' });
  expect(within(feed).getByRole('link', { name: 'stranger' })).toHaveAttribute('href', '/profile?user=stranger');
  expect(within(feed).getByText('Своё железо (Verilog)')).toBeVisible();
  expect(await screen.findByRole('link', { name: 'Раунд на Verilog' })).toHaveAttribute('href', '/contest/round');

  // every processor has a rating of its own: the first of the catalogue is shown until another is picked
  const rating = await screen.findByRole('table', { name: 'Рейтинг участников' });
  expect(within(rating).getByText('2100')).toBeVisible();
  expect(listUsers).toHaveBeenCalledWith(expect.objectContaining({ sort: 'rating', processor: 'gpu-cuda' }), expect.any(AbortSignal));
  await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Процессор рейтинга' }), 'rtl');
  await waitFor(() => expect(within(screen.getByRole('table', { name: 'Рейтинг участников' })).getByText('1500')).toBeVisible());
  expect(screen.queryByText('2100')).not.toBeInTheDocument();
});
