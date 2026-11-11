import { describe, expect, expectTypeOf, it, vi } from 'vitest';
import { ApiError, createApi } from '../src/api/client';
import type { Problem, ProblemDetail, User } from '../src/api/types';

const user: User = {
  id: 'new_cuda', handle: 'new_cuda', ratings: {},
};

function capture(handler: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  const fetcher = vi.fn<typeof fetch>(async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    requests.push(request);
    return handler(request);
  });
  return { requests, fetcher };
}

describe('API client', () => {
  it('uses generated response types and includes session credentials on every request', async () => {
    type Client = ReturnType<typeof createApi>;
    expectTypeOf<ReturnType<Client['session']>>().toEqualTypeOf<Promise<User>>();
    expectTypeOf<ReturnType<Client['getProblem']>>().toMatchTypeOf<Promise<ProblemDetail>>();
    const { requests, fetcher } = capture(request => request.method === 'DELETE'
      ? new Response(null, { status: 204 })
      : Response.json(user));
    const api = createApi('http://localhost/api', fetcher);
    expect(await api.session()).toEqual(user);
    expect(await api.login({ identifier: 'new_cuda', password: 'password123' })).toEqual(user);
    await expect(api.logout()).resolves.toBeUndefined();
    expect(requests.map(request => request.method)).toEqual(['GET', 'POST', 'DELETE']);
    expect(requests.every(request => request.credentials === 'include')).toBe(true);
    expect(requests.every(request => new URL(request.url).pathname === '/api/session')).toBe(true);
    expect(requests[1]!.headers.get('Content-Type')).toContain('application/json');
    expect(await requests[1]!.json()).toEqual({ identifier: 'new_cuda', password: 'password123' });
  });

  it('preserves query filters, explicit practice or contest context, code and cancellation', async () => {
    const { requests, fetcher } = capture(request => Response.json(request.method === 'POST'
      ? { passed: true, diagnostic: '' }
      : { items: [], total: 0, tags: [] }));
    const api = createApi('http://localhost/api', fetcher);
    const controller = new AbortController();
    await api.listProblems({
      q: 'a + b', difficulty: 'medium', tag: 'Массивы', solved: false,
      userId: 'new_cuda', context: 'weekly-12', offset: 20, limit: 10, processor: 'rtl', sort: 'bestScore',
    }, controller.signal);
    const query = new URL(requests[0]!.url).searchParams;
    expect(Object.fromEntries(query)).toEqual({
      q: 'a + b', difficulty: 'medium', tag: 'Массивы', solved: 'false',
      userId: 'new_cuda', context: 'weekly-12', offset: '20', limit: '10', processor: 'rtl', sort: 'bestScore',
    });
    controller.abort();
    expect(requests[0]!.signal.aborted).toBe(true);
    await api.getProblem('vector-addition');
    expect(new URL(requests[1]!.url).searchParams.get('context')).toBe('practice');
    const code = '#include <cuda_runtime.h>\nvoid solve() {}\n';
    await api.checkExample('vector-addition', 'weekly-12', { code, processor: 'gpu-cuda', language: 'cuda' });
    expect(new URL(requests[2]!.url).pathname).toBe('/api/problems/vector-addition/example');
    expect(new URL(requests[2]!.url).searchParams.get('context')).toBe('weekly-12');
    expect(await requests[2]!.json()).toEqual({ code, processor: 'gpu-cuda', language: 'cuda' });
    await api.listSubmissions({ userId: 'alex_cuda', context: 'all', status: 'wrong_answer' });
    expect(Object.fromEntries(new URL(requests[3]!.url).searchParams)).toEqual({
      userId: 'alex_cuda', context: 'all', status: 'wrong_answer',
    });
  });

  it('keeps server status and message and provides a fallback for an empty error object', async () => {
    const { fetcher } = capture(() => Response.json({ message: 'Сначала зарегистрируйтесь в контесте.' }, { status: 403 }));
    await expect(createApi('http://localhost/api', fetcher).session()).rejects.toMatchObject({
      name: 'ApiError', status: 403, message: 'Сначала зарегистрируйтесь в контесте.',
    });
    const fallback = capture(() => Response.json({}, { status: 503 }));
    await expect(createApi('http://localhost/api', fallback.fetcher).session()).rejects.toMatchObject({
      status: 503, message: 'Ошибка сервера (503).',
    });
    const empty = capture(() => new Response(null, { status: 200, headers: { 'Content-Length': '0' } }));
    await expect(createApi('http://localhost/api', empty.fetcher).session()).rejects.toMatchObject({
      status: 502, message: 'Сервер вернул пустой ответ.',
    });
  });

  it('distinguishes network failure from an aborted request', async () => {
    const offline = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(createApi('http://localhost/api', offline).session()).rejects.toMatchObject({
      name: 'ApiError', status: 0,
    });
    const aborted = new DOMException('Cancelled', 'AbortError');
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(aborted);
    await expect(createApi('http://localhost/api', fetcher).session()).rejects.toBe(aborted);
    expect(aborted).not.toBeInstanceOf(ApiError);
  });

  it('loads every problem beyond the 100 item limit and keeps the server order', async () => {
    const problems: Problem[] = Array.from({ length: 237 }, (_, index) => ({
      id: `problem-${index}`, code: String(index).padStart(3, '0'), title: `Problem ${index}`,
      difficulty: 'easy', tags: [], solved: false, solvedCount: 0,
      lastSubmissionId: null, lastStatus: null, processors: [{ id: 'gpu-cuda', languages: ['cuda'], bestSubmissionId: null, bestScore: null, recordScore: null }],
    }));
    const { requests, fetcher } = capture(async request => {
      const query = new URL(request.url).searchParams;
      const offset = Number(query.get('offset'));
      const limit = Number(query.get('limit'));
      if (offset === 100) await new Promise(resolve => setTimeout(resolve, 15));
      return Response.json({ items: problems.slice(offset, offset + limit), total: problems.length, tags: [] });
    });
    const result = await createApi('http://localhost/api', fetcher).allProblems({
      context: 'weekly-12', tag: 'Массивы', userId: 'new_cuda', offset: 99, limit: 1,
    });
    expect(result).toEqual(problems);
    expect(new Set(result.map(problem => problem.id)).size).toBe(problems.length);
    expect(requests.map(request => new URL(request.url).searchParams.get('offset'))).toEqual(['0', '100', '200']);
    for (const request of requests) {
      const query = new URL(request.url).searchParams;
      expect(query.get('limit')).toBe('100');
      expect(query.get('context')).toBe('weekly-12');
      expect(query.get('tag')).toBe('Массивы');
      expect(query.get('userId')).toBe('new_cuda');
    }
  });
});
