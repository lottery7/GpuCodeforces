import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createApiServer } from './fixtures/api-server.mjs';

let server;
let base;

async function request(path, { method = 'GET', body, cookie } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(cookie ? { Cookie: cookie } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  return {
    status: response.status,
    body: response.status === 204 ? null : await response.json(),
    cookie: response.headers.get('set-cookie')?.split(';')[0],
  };
}

async function login(identifier) {
  const response = await request('/session', {
    method: 'POST', body: { identifier, password: 'password123' },
  });
  expect(response.status).toBe(200);
  expect(response.cookie).toMatch(/^session=.+/);
  return response.cookie;
}

async function waitForStatus(id, status, cookie) {
  const deadline = Date.now() + 4000;
  while (Date.now() < deadline) {
    const response = await request(`/submissions/${id}`, { cookie });
    expect(response.status).toBe(200);
    if (response.body.status === status) return response.body;
    await delay(75);
  }
  throw new Error(`Посылка ${id} не получила статус ${status}`);
}

beforeEach(async () => {
  server = createApiServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  base = `http://127.0.0.1:${server.address().port}/api`;
});

afterEach(async () => {
  server.closeAllConnections();
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

describe('Fixture HTTP API', () => {
  it('keeps sessions independent and restricts submission source to its owner', async () => {
    const [alex, newcomer] = await Promise.all([login('alex_cuda'), login('new_cuda')]);
    expect((await request('/session')).status).toBe(401);
    expect((await request('/session', { cookie: alex })).body.id).toBe('alex_cuda');
    expect((await request('/session', { cookie: newcomer })).body.id).toBe('new_cuda');

    const own = (await request('/submissions/1042', { cookie: alex })).body;
    expect(own.code).toContain('void solve');
    expect(own).toHaveProperty('diagnostic');
    for (const cookie of [newcomer, undefined]) {
      const other = (await request('/submissions/1042', { cookie })).body;
      expect(other).not.toHaveProperty('code');
      expect(other).not.toHaveProperty('diagnostic');
      expect(other).toHaveProperty('environmentMarkdown');
    }
    const history = (await request('/submissions?userId=alex_cuda')).body;
    expect(history.total).toBeGreaterThan(0);
    expect(history.items.every(item => !('code' in item) && !('diagnostic' in item))).toBe(true);
    expect((await request('/session', { method: 'DELETE', cookie: alex })).status).toBe(204);
    expect((await request('/session', { cookie: alex })).status).toBe(401);
    expect((await request('/session', { cookie: newcomer })).status).toBe(200);
  });

  it('starts new users without results, rating or automatic contest participation', async () => {
    const response = await request('/users', {
      method: 'POST', body: { handle: 'fresh_user', email: 'fresh@example.com', password: 'newpassword123' },
    });
    expect(response.status).toBe(201);
    expect(response.body).toMatchObject({ id: 'fresh_user', ratings: {} });
    const cookie = response.cookie;
    expect((await request('/session', { cookie })).body.id).toBe('fresh_user');
    expect((await request('/users/fresh_user')).body).toMatchObject({ solvedCount: 0, contests: [] });
    expect((await request('/users/new_cuda')).body).toMatchObject({ solvedCount: 0, contests: [] });
    expect((await request('/problems?solved=true', { cookie })).body.total).toBe(0);
    expect((await request('/contests?mine=true', { cookie })).body).toEqual([]);
    expect((await request('/users?sort=rating&processor=gpu-cuda&q=fresh_user')).body).toEqual({ items: [], total: 0 });
    expect((await request('/contests', { cookie })).body.every(contest => !contest.registered)).toBe(true);
    expect((await request('/session', {
      method: 'POST', body: { identifier: 'fresh_user', password: 'password123' },
    })).status).toBe(401);
    expect((await request('/session', {
      method: 'POST', body: { identifier: 'fresh_user', password: 'newpassword123' },
    })).status).toBe(200);
  });

  it('hides upcoming tasks from ordinary visitors', async () => {
    const author = await login('author_cuda');
    const hidden = (await request('/contests/weekly-13')).body;
    expect(hidden.problemCount).toBe(3);
    expect(hidden.problemIds).toEqual([]);
    expect(hidden.rulesMarkdown).not.toContain('Сложение векторов');
    expect((await request('/problems?context=weekly-13')).status).toBe(403);
    expect((await request('/problems/vector-addition?context=weekly-13')).status).toBe(403);
    expect((await request('/problems/vector-addition/leaders?context=weekly-13')).status).toBe(403);
    const visible = (await request('/contests/weekly-13', { cookie: author })).body;
    expect(visible.problemIds).toHaveLength(3);
    expect(visible.rulesMarkdown).toContain('Сложение векторов');
    expect((await request('/problems?context=weekly-13', { cookie: author })).body.total).toBe(3);
    expect((await request('/contests/weekly-13/standings', { cookie: author })).body).toEqual([]);
    expect((await request('/problems')).body.total).toBe(8);
  });

  it('allows removing the creator while keeping at least one author', async () => {
    const alex = await login('alex_cuda');
    const newcomer = await login('new_cuda');
    const start = Date.now() + 86400000;
    const input = {
      title: 'Team Contest', startAt: new Date(start).toISOString(), endAt: new Date(start + 3600000).toISOString(),
      authorIds: ['alex_cuda', 'new_cuda'], problemIds: ['reduction', 'vector-addition'], processor: 'gpu-cuda',
    };
    const created = await request('/contests', { method: 'POST', body: input, cookie: alex });
    expect(created.status).toBe(201);
    const id = created.body.id;
    expect(created.body.registered).toBe(false);
    expect(created.body.problemIds).toEqual(input.problemIds);
    expect(created.body.authors.map(author => author.id)).toEqual(input.authorIds);
    expect((await request('/contests?mine=true', { cookie: alex })).body.some(contest => contest.id === id)).toBe(true);
    expect((await request(`/contests/${id}`, {
      method: 'PUT', body: { ...input, authorIds: [] }, cookie: alex,
    })).status).toBe(400);
    expect((await request(`/contests/${id}`, { cookie: alex })).body.authors).toHaveLength(2);
    const edited = await request(`/contests/${id}`, {
      method: 'PUT', body: { ...input, authorIds: ['new_cuda'] }, cookie: alex,
    });
    expect(edited.status).toBe(200);
    expect(edited.body.authors.map(author => author.id)).toEqual(['new_cuda']);
    expect((await request(`/contests/${id}`, { method: 'PUT', body: input, cookie: alex })).status).toBe(403);
    expect((await request(`/contests/${id}`, {
      method: 'PUT', body: { ...input, title: 'Updated Contest', authorIds: ['new_cuda'] }, cookie: newcomer,
    })).status).toBe(200);
    expect((await request('/contests?mine=true', { cookie: alex })).body.some(contest => contest.id === id)).toBe(false);
    expect((await request('/contests?mine=true', { cookie: newcomer })).body.some(contest => contest.id === id)).toBe(true);
    expect((await request('/users/new_cuda')).body.contests).toEqual([]);
  });

  it('rechecks a temporary failure without changing submission identity or contest context', async () => {
    const newcomer = await login('new_cuda');
    const alex = await login('alex_cuda');
    const input = { problemId: 'vector-addition', contestId: 'weekly-12', code: 'FIXTURE_SYSTEM_ERROR', processor: 'gpu-cuda', language: 'cuda' };
    expect((await request('/submissions', { method: 'POST', body: input })).status).toBe(401);
    expect((await request('/submissions', { method: 'POST', body: input, cookie: newcomer })).status).toBe(400);
    expect((await request('/contests/weekly-12/registration', { method: 'PUT', cookie: newcomer })).status).toBe(204);
    expect((await request('/contests/weekly-12/registration', { method: 'PUT', cookie: newcomer })).status).toBe(204);
    const queued = await request('/submissions', { method: 'POST', body: input, cookie: newcomer });
    expect(queued.status).toBe(202);
    expect(queued.body.status).toBe('queued');
    expect(queued.body).not.toHaveProperty('code');
    const failed = await waitForStatus(queued.body.id, 'system_error', newcomer);
    expect(failed.score).toBeNull();
    expect(failed.comparable).toBe(false);
    expect((await request(`/submissions/${failed.id}/recheck`, { method: 'POST', cookie: alex })).status).toBe(403);
    const retry = await request(`/submissions/${failed.id}/recheck`, { method: 'POST', cookie: newcomer });
    expect(retry.status).toBe(202);
    expect(retry.body.status).toBe('queued');
    const accepted = await waitForStatus(failed.id, 'accepted', newcomer);
    expect(accepted).toMatchObject({
      id: failed.id, code: failed.code, createdAt: failed.createdAt, contestId: failed.contestId,
      environmentMarkdown: failed.environmentMarkdown, previousBestScore: failed.previousBestScore,
      comparable: true, passedTests: accepted.totalTests,
    });
    expect(accepted.score.value).toBeGreaterThan(0);
    expect(accepted.score.type).toBe('TIME_MS');
    const standings = (await request('/contests/weekly-12/standings')).body;
    // Solve time counts from the start to when the submission was sent, not to its (re)check.
    const contest = (await request('/contests/weekly-12')).body;
    expect(standings.find(row => row.user.id === 'new_cuda').times['vector-addition'])
      .toBe(Math.floor((Date.parse(failed.createdAt) - Date.parse(contest.startAt)) / 1000));
    expect((await request(`/submissions/${accepted.id}/recheck`, { method: 'POST', cookie: newcomer })).status).toBe(400);
  }, 10000);

  it('keeps practice records separate and examples out of submission history', async () => {
    const alex = await login('alex_cuda');
    const bank = (await request('/problems?q=001&difficulty=easy&solved=true', { cookie: alex })).body;
    expect(bank.items.map(problem => problem.id)).toEqual(['vector-addition']);
    expect(bank.tags.length).toBeGreaterThan(1);
    const rating = (await request('/users?sort=rating&processor=gpu-cuda&q=alex')).body;
    expect(rating.items[0].ratings['gpu-cuda'].rank).toBe(4);
    expect((await request('/users?sort=rating')).status).toBe(400);
    const all = (await request('/submissions?context=all', { cookie: alex })).body;
    const practice = (await request('/submissions?context=practice', { cookie: alex })).body;
    expect(all.total).toBeGreaterThan(practice.total);
    expect(practice.items.every(item => item.contestId === null)).toBe(true);
    const archive = (await request('/submissions/1005', { cookie: alex })).body;
    expect(archive.status).toBe('accepted');
    expect(archive.comparable).toBe(false);
    expect(archive.environmentMarkdown).toContain('demo-v0');
    const problem = (await request('/problems/vector-addition', { cookie: alex })).body;
    const starter = problem.processorDetails.find(item => item.id === 'gpu-cuda').starters.cuda;
    expect(starter).toContain('void solve');
    expect(problem.processorDetails.map(item => item.limit.type)).toEqual(['TIME_MS', 'CYCLES_X_DEPTH_X_TRANSISTORS']);
    const leaders = (await request('/problems/vector-addition/leaders?processor=gpu-cuda')).body;
    expect(leaders[0].score.value).toBeLessThanOrEqual(leaders.at(-1).score.value);
    expect((await request('/problems/vector-addition/leaders?processor=rtl')).body).toEqual([]);
    const target = { processor: 'gpu-cuda', language: 'cuda' };
    expect((await request('/problems/vector-addition/example', {
      method: 'POST', body: { code: 'FIXTURE_WRONG_ANSWER', ...target }, cookie: alex,
    })).body).toMatchObject({ passed: false });
    expect((await request('/problems/vector-addition/example', {
      method: 'POST', body: { code: starter, ...target }, cookie: alex,
    })).body).toEqual({ passed: true, diagnostic: '' });
    expect((await request('/submissions?context=all', { cookie: alex })).body.total).toBe(all.total);
    const contests = (await request('/contests?q=12&phase=live')).body;
    expect(contests.map(contest => contest.id)).toEqual(['weekly-12']);
    const paginated = (await request('/problems?offset=1&limit=2')).body;
    expect(paginated.total).toBe(8);
    expect(paginated.items).toHaveLength(2);
  });

  it('returns canonical terminal verdicts and counts passed correctness tests', async () => {
    const alex = await login('alex_cuda');
    const statuses = ['compile_error', 'wrong_answer', 'runtime_error', 'time_limit', 'memory_limit', 'measurement_error'];
    const ids = await Promise.all(statuses.map(async status => {
      const response = await request('/submissions', {
        method: 'POST', body: { problemId: 'softmax', code: `FIXTURE_${status.toUpperCase()}`, processor: 'gpu-cuda', language: 'cuda' }, cookie: alex,
      });
      expect(response.status).toBe(202);
      return response.body.id;
    }));
    const results = await Promise.all(ids.map((id, index) => waitForStatus(id, statuses[index], alex)));
    expect(results.every(item => item.score === null && item.comparable === false && item.diagnostic.length > 0)).toBe(true);
    expect(results[1].passedTests).toBe(6);
    expect(results.at(-1).passedTests).toBe(results.at(-1).totalTests);
    expect((await request('/problems/softmax', { cookie: alex })).body.solved).toBe(true);
    expect((await request('/submissions?problemId=softmax&status=wrong_answer', { cookie: alex })).body.total).toBe(1);
  }, 7000);

  it('returns full long diagnostics only to the owner and preserves context on recheck', async () => {
    const owner = await login('new_cuda');
    const other = await login('alex_cuda');
    const code = 'FIXTURE_LONG_DIAGNOSTIC';
    const target = { processor: 'gpu-cuda', language: 'cuda' };
    const example = await request('/problems/vector-addition/example', { method: 'POST', body: { code, ...target }, cookie: owner });
    expect(example.body.passed).toBe(false);
    expect(example.body.diagnostic).toHaveLength(16384);
    expect(example.body.diagnostic.split('\n').length).toBeGreaterThan(100);
    const queued = await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', code, ...target }, cookie: owner });
    const failed = await waitForStatus(queued.body.id, 'system_error', owner);
    expect(failed.diagnostic).toBe(example.body.diagnostic);
    expect(failed).toMatchObject({ code, contestId: null, createdAt: queued.body.createdAt });
    for (const cookie of [other, undefined]) {
      const detail = await request(`/submissions/${failed.id}`, { cookie });
      expect(detail.body).not.toHaveProperty('diagnostic');
      expect(detail.body).not.toHaveProperty('code');
    }
    expect((await request(`/submissions/${failed.id}/recheck`, { method: 'POST', cookie: owner })).status).toBe(202);
    const accepted = await waitForStatus(failed.id, 'accepted', owner);
    expect(accepted).toMatchObject({ code, diagnostic: '', contestId: null, createdAt: failed.createdAt, environmentMarkdown: failed.environmentMarkdown });
  }, 10000);

  it('offers processors: filters, a submission on rtl with its own score, and contests on a processor', async () => {
    const alex = await login('alex_cuda');
    const processors = (await request('/processors')).body;
    expect(processors.map(item => item.id)).toEqual(['gpu-cuda', 'rtl']);
    expect((await request('/problems?processor=rtl')).body.items.map(problem => problem.id)).toEqual(['vector-addition']);
    expect((await request('/problems?sort=bestScore')).status).toBe(400);
    expect((await request('/submissions', { method: 'POST', body: { problemId: 'softmax', code: 'x', processor: 'rtl', language: 'systemverilog' }, cookie: alex })).status).toBe(400);
    expect((await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', code: 'x', processor: 'rtl', language: 'cuda' }, cookie: alex })).status).toBe(400);
    const queued = await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', code: 'module solve; endmodule', processor: 'rtl', language: 'systemverilog' }, cookie: alex });
    expect(queued.status).toBe(202);
    const accepted = await waitForStatus(queued.body.id, 'accepted', alex);
    expect(accepted).toMatchObject({ processor: 'rtl', language: 'systemverilog', score: { type: 'CYCLES_X_DEPTH_X_TRANSISTORS' }, metrics: { cycles: 64, depth: 4, transistors: 16 } });
    const start = new Date(Date.now() + 86400000).toISOString();
    const contest = { title: 'Схемы', startAt: start, endAt: new Date(Date.parse(start) + 3600000).toISOString(), authorIds: ['alex_cuda'], problemIds: ['vector-addition'], processor: 'rtl' };
    expect((await request('/contests', { method: 'POST', body: { ...contest, problemIds: ['softmax'] }, cookie: alex })).status).toBe(400);
    const created = await request('/contests', { method: 'POST', body: contest, cookie: alex });
    expect(created.body.processor).toBe('rtl');
    expect((await request('/contests?processor=rtl')).body.map(item => item.id)).toEqual([created.body.id]);
  }, 7000);
});
