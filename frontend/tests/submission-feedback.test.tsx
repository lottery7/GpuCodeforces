import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../src/api/client';
import type { Submission as SubmissionRecord, User } from '../src/api/types';
import Submission from '../src/pages/Submission';

const owner: User = { id: 'owner', handle: 'cuda_user', rating: null, rank: null, ratingDelta: null, ratingContestId: null };

vi.mock('../src/auth', () => ({ useSession: () => ({ user: owner, isLoading: false, error: null }) }));
vi.mock('../src/components/CodeEditor', () => ({ default: () => <div>Исходный код</div> }));

const record: SubmissionRecord = {
  id: '42', problemId: 'vector-add', problemTitle: 'Сложение векторов', format: 'standard', user: owner,
  contestId: null, contestTitle: null, createdAt: '2026-01-01T10:00:00Z', status: 'system_error',
  timeMs: null, comparable: false, code: 'void solve() {}', diagnostic: 'GPU недоступна.\n'.repeat(100),
};

describe('Результат повторной проверки', () => {
  it('держит действие и его ошибку перед длинной диагностикой', async () => {
    vi.spyOn(api, 'getSubmission').mockResolvedValue(record);
    vi.spyOn(api, 'recheckSubmission').mockRejectedValue(new Error('Не удалось вернуть посылку в очередь.'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/submission/42']}><Routes>
      <Route path="/submission/:id" element={<Submission />} />
    </Routes></MemoryRouter></QueryClientProvider>);

    const retry = await screen.findByRole('button', { name: 'Повторить проверку' });
    const diagnostic = screen.getByText(/GPU недоступна\./);
    expect(retry.compareDocumentPosition(diagnostic) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await userEvent.click(retry);
    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('Не удалось вернуть посылку в очередь.');
    expect(retry.compareDocumentPosition(error) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(error.compareDocumentPosition(diagnostic) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Ошибка системы' })).toBeVisible();
  });
});
