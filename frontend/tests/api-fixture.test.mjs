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
    expect(response.body).toMatchObject({ id: 'fresh_user', rating: null, rank: null, ratingDelta: null });
    const cookie = response.cookie;
    expect((await request('/session', { cookie })).body.id).toBe('fresh_user');
    expect((await request('/users/fresh_user')).body).toMatchObject({ solvedCount: 0, contests: [] });
    expect((await request('/users/new_cuda')).body).toMatchObject({ solvedCount: 0, contests: [] });
    expect((await request('/problems?solved=true', { cookie })).body.total).toBe(0);
    expect((await request('/contests?mine=true', { cookie })).body).toEqual([]);
    expect((await request('/users?sort=rating&q=fresh_user')).body).toEqual({ items: [], total: 0 });
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
    expect((await request('/problems')).body.total).toBe(10);
  });

  it('allows removing the creator while keeping at least one author', async () => {
    const alex = await login('alex_cuda');
    const newcomer = await login('new_cuda');
    const start = Date.now() + 86400000;
    const input = {
      title: 'Team Contest', startAt: new Date(start).toISOString(), endAt: new Date(start + 3600000).toISOString(),
      authorIds: ['alex_cuda', 'new_cuda'], problemIds: ['reduction', 'vector-addition'],
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
    const input = { problemId: 'vector-addition', contestId: 'weekly-12', code: 'FIXTURE_SYSTEM_ERROR' };
    expect((await request('/submissions', { method: 'POST', body: input })).status).toBe(401);
    expect((await request('/submissions', { method: 'POST', body: input, cookie: newcomer })).status).toBe(400);
    expect((await request('/contests/weekly-12/registration', { method: 'PUT', cookie: newcomer })).status).toBe(204);
    expect((await request('/contests/weekly-12/registration', { method: 'PUT', cookie: newcomer })).status).toBe(204);
    const queued = await request('/submissions', { method: 'POST', body: input, cookie: newcomer });
    expect(queued.status).toBe(202);
    expect(queued.body.status).toBe('queued');
    expect(queued.body).not.toHaveProperty('code');
    const failed = await waitForStatus(queued.body.id, 'system_error', newcomer);
    expect(failed.timeMs).toBeNull();
    expect(failed.comparable).toBe(false);
    expect((await request(`/submissions/${failed.id}/recheck`, { method: 'POST', cookie: alex })).status).toBe(403);
    const retry = await request(`/submissions/${failed.id}/recheck`, { method: 'POST', cookie: newcomer });
    expect(retry.status).toBe(202);
    expect(retry.body.status).toBe('queued');
    const accepted = await waitForStatus(failed.id, 'accepted', newcomer);
    expect(accepted).toMatchObject({
      id: failed.id, code: failed.code, createdAt: failed.createdAt, contestId: failed.contestId,
      environmentMarkdown: failed.environmentMarkdown, previousBestTimeMs: failed.previousBestTimeMs,
      comparable: true, passedTests: accepted.totalTests,
    });
    expect(accepted.timeMs).toBeGreaterThan(0);
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
    const rating = (await request('/users?sort=rating&q=alex')).body;
    expect(rating.items[0].rank).toBe(4);
    const all = (await request('/submissions?context=all', { cookie: alex })).body;
    const practice = (await request('/submissions?context=practice', { cookie: alex })).body;
    expect(all.total).toBeGreaterThan(practice.total);
    expect(practice.items.every(item => item.contestId === null)).toBe(true);
    const archive = (await request('/submissions/1005', { cookie: alex })).body;
    expect(archive.status).toBe('accepted');
    expect(archive.comparable).toBe(false);
    expect(archive.environmentMarkdown).toContain('demo-v0');
    const problem = (await request('/problems/vector-addition', { cookie: alex })).body;
    expect(problem.starterCode).toContain('void solve');
    expect(problem).toHaveProperty('timeLimitSeconds');
    const leaders = (await request('/problems/vector-addition/leaders')).body;
    expect(leaders[0].timeMs).toBeLessThanOrEqual(leaders.at(-1).timeMs);
    expect((await request('/problems/vector-addition/example', {
      method: 'POST', body: { code: 'FIXTURE_WRONG_ANSWER' }, cookie: alex,
    })).body).toMatchObject({ passed: false });
    expect((await request('/problems/vector-addition/example', {
      method: 'POST', body: { code: problem.starterCode }, cookie: alex,
    })).body).toEqual({ passed: true, diagnostic: '' });
    expect((await request('/submissions?context=all', { cookie: alex })).body.total).toBe(all.total);
    const contests = (await request('/contests?q=12&phase=live')).body;
    expect(contests.map(contest => contest.id)).toEqual(['weekly-12']);
    const paginated = (await request('/problems?offset=1&limit=2')).body;
    expect(paginated.total).toBe(10);
    expect(paginated.items).toHaveLength(2);
  });

  it('returns canonical terminal verdicts and counts passed correctness tests', async () => {
    const alex = await login('alex_cuda');
    const statuses = ['compile_error', 'wrong_answer', 'runtime_error', 'time_limit', 'memory_limit', 'measurement_error'];
    const ids = await Promise.all(statuses.map(async status => {
      const response = await request('/submissions', {
        method: 'POST', body: { problemId: 'softmax', code: `FIXTURE_${status.toUpperCase()}` }, cookie: alex,
      });
      expect(response.status).toBe(202);
      return response.body.id;
    }));
    const results = await Promise.all(ids.map((id, index) => waitForStatus(id, statuses[index], alex)));
    expect(results.every(item => item.timeMs === null && item.comparable === false && item.diagnostic.length > 0)).toBe(true);
    expect(results[1].passedTests).toBe(6);
    expect(results.at(-1).passedTests).toBe(results.at(-1).totalTests);
    expect((await request('/problems/softmax', { cookie: alex })).body.solved).toBe(true);
    expect((await request('/submissions?problemId=softmax&status=wrong_answer', { cookie: alex })).body.total).toBe(1);
  }, 7000);

  it('returns full long diagnostics only to the owner and preserves context on recheck', async () => {
    const owner = await login('new_cuda');
    const other = await login('alex_cuda');
    const code = 'FIXTURE_LONG_DIAGNOSTIC';
    const example = await request('/problems/vector-addition/example', { method: 'POST', body: { code }, cookie: owner });
    expect(example.body.passed).toBe(false);
    expect(example.body.diagnostic).toHaveLength(16384);
    expect(example.body.diagnostic.split('\n').length).toBeGreaterThan(100);
    const queued = await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', code }, cookie: owner });
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

  it('keeps guesses immutable, restores the attempt and hides the answer from other users', async () => {
    const owner = await login('new_cuda');
    const other = await login('alex_cuda');
    const detail = (await request('/problems/guess-vector', { cookie: owner })).body;
    expect(detail).toMatchObject({ format: 'guess', guessSubmissionId: null });
    expect(detail.firstCode).toContain('void solve');
    expect(detail).not.toHaveProperty('firstTimeMs');
    const guess = await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: detail.referenceMeasurement.key }, cookie: owner });
    expect(guess.status).toBe(200);
    expect(guess.body).toMatchObject({ status: 'accepted', timeMs: null, guessResult: { choice: 'first', correctChoice: 'first' } });
    expect((await request('/problems/guess-vector', { cookie: owner })).body.guessSubmissionId).toBe(guess.body.id);
    const repeated = await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: detail.referenceMeasurement.key }, cookie: owner });
    expect(repeated.body.id).toBe(guess.body.id);
    expect((await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'second', benchmarkKey: detail.referenceMeasurement.key }, cookie: owner })).status).toBe(400);
    expect((await request(`/submissions/${guess.body.id}`, { cookie: other })).body).not.toHaveProperty('guessResult');
    expect((await request('/problems?format=guess', { cookie: owner })).body.items.every(item => item.format === 'guess')).toBe(true);
    expect((await request('/submissions', { method: 'POST', body: { problemId: 'guess-vector', code: 'void solve() {}' }, cookie: owner })).status).toBe(400);
  });

  it('matches random searches and chooses one coding task after matching', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const outsider = await login('author_cuda');
    const queued = await request('/duels', { method: 'POST', body: { mode: 'random' }, cookie: owner });
    expect(queued.status).toBe(201);
    expect(queued.body).toMatchObject({ status: 'queued', contestId: null });
    expect(queued.body.problemId).toBeNull();
    const matched = await request('/duels', { method: 'POST', body: { mode: 'random' }, cookie: opponent });
    expect(matched.status).toBe(201);
    expect(matched.body.status).toBe('matched');
    expect((await request('/duels/current', { cookie: owner })).status).toBe(204);
    const current = (await request(`/duels/${queued.body.id}`, { cookie: owner })).body;
    expect(current.contestId).toBe(matched.body.contestId);
    const room = (await request(`/contests/${current.contestId}`, { cookie: owner })).body;
    expect(room).toMatchObject({ format: 'duel', registered: true, problemCount: 1 });
    expect((await request(`/contests/${room.id}`, { cookie: opponent })).body.registered).toBe(true);
    const task = (await request(`/problems/${room.problemIds[0]}?context=${room.id}`, { cookie: owner })).body;
    expect(task.format).not.toBe('guess');
    const hours = { easy: 1, medium: 2, hard: 3 }[task.difficulty];
    expect(Date.parse(room.endAt) - Date.parse(room.startAt)).toBe(hours * 3600000);
    expect((await request('/duels', { cookie: owner })).body).toMatchObject({ total: 1, items: [{ contestId: room.id }] });
    expect((await request('/contests', { cookie: owner })).body.every(item => item.format === 'standard')).toBe(true);
    expect((await request(`/contests/${room.id}/registration`, { method: 'PUT', cookie: outsider })).status).toBe(403);
    expect((await request(`/duels/${queued.body.id}`, { cookie: outsider })).status).toBe(403);
  });

  it('delivers a private persisted invitation and starts a duel only after acceptance', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const outsider = await login('author_cuda');
    const created = await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda', problemId: 'vector-addition' }, cookie: owner });
    expect(created.body).toMatchObject({ status: 'invited', contestId: null });
    expect((await request('/duels/current', { cookie: opponent })).status).toBe(204);
    const notes = (await request('/notifications', { cookie: opponent })).body;
    expect(notes.unreadCount).toBe(1);
    expect(notes.items[0]).toMatchObject({ type: 'duel_invitation', duelRequestId: created.body.id, duelStatus: 'invited' });
    expect((await request('/notifications', { cookie: outsider })).body.items).toEqual([]);
    expect((await request(`/notifications/${notes.items[0].id}/read`, { method: 'PUT', cookie: outsider })).status).toBe(404);
    expect((await request(`/notifications/${notes.items[0].id}/read`, { method: 'PUT', cookie: opponent })).status).toBe(204);
    expect((await request('/notifications', { cookie: opponent })).body.unreadCount).toBe(0);
    expect((await request(`/duels/${created.body.id}/accept`, { method: 'POST', cookie: owner })).status).toBe(403);
    const accepted = await request(`/duels/${created.body.id}/accept`, { method: 'POST', cookie: opponent });
    expect(accepted.body.status).toBe('matched');
    expect((await request('/duels/current', { cookie: owner })).status).toBe(204);
    expect(accepted.body.problemId).toBe('vector-addition');
    expect((await request('/duels', { cookie: owner })).body.items[0].contestId).toBe(accepted.body.contestId);
    expect((await request('/notifications', { cookie: owner })).body.items[0]).toMatchObject({ type: 'duel_started', contestId: accepted.body.contestId });
  });

  it('persists declined and cancelled invitations and prevents accepting stale invitations', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const body = { mode: 'invite', opponentHandle: 'new_cuda' };
    const first = (await request('/duels', { method: 'POST', body, cookie: owner })).body;
    expect((await request(`/duels/${first.id}/decline`, { method: 'POST', cookie: opponent })).body.status).toBe('declined');
    expect((await request('/duels/current', { cookie: owner })).status).toBe(204);
    expect((await request('/notifications', { cookie: owner })).body.items[0]).toMatchObject({ type: 'duel_declined', duelStatus: 'declined' });
    const second = (await request('/duels', { method: 'POST', body, cookie: owner })).body;
    expect((await request(`/duels/${second.id}/cancel`, { method: 'POST', cookie: owner })).body.status).toBe('cancelled');
    expect((await request(`/duels/${second.id}/accept`, { method: 'POST', cookie: opponent })).status).toBe(400);
    const notifications = (await request('/notifications', { cookie: opponent })).body.items;
    expect(notifications.find(item => item.type === 'duel_invitation' && item.duelRequestId === second.id).duelStatus).toBe('cancelled');
    expect(notifications[0].type).toBe('duel_cancelled');
    expect((await request('/runtime')).body).toEqual({ executionMode: 'mock', gpuName: null });
  });

  it('keeps simultaneous duels and invitations independent with paginated private lists', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const outsider = await login('author_cuda');
    const first = (await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda', problemId: 'vector-addition' }, cookie: owner })).body;
    const second = (await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda' }, cookie: owner })).body;
    const queue = (await request('/duels', { method: 'POST', body: { mode: 'random' }, cookie: owner })).body;
    expect((await request('/duels', { method: 'POST', body: { mode: 'random' }, cookie: owner })).body.id).toBe(queue.id);
    const rooms = [];
    for (const invite of [first, second]) rooms.push((await request(`/duels/${invite.id}/accept`, { method: 'POST', cookie: opponent })).body.contestId);
    expect(new Set(rooms).size).toBe(2);
    expect((await request('/duels/current', { cookie: owner })).body.id).toBe(queue.id);
    const own = (await request('/duels?phase=active&limit=2', { cookie: owner })).body;
    expect(own.total).toBe(3);
    expect(own.items).toHaveLength(2);
    const next = (await request('/duels?phase=active&limit=2&offset=2', { cookie: owner })).body;
    expect(next.items).toHaveLength(1);
    expect(new Set([...own.items, ...next.items].map(item => item.id)).size).toBe(3);
    expect((await request('/duels', { cookie: opponent })).body.total).toBe(2);
    expect((await request('/duels', { cookie: outsider })).body).toEqual({ items: [], total: 0 });
    expect((await request('/duels?phase=finished', { cookie: owner })).body.total).toBe(0);
    expect((await request('/duels', { method: 'POST', body: { mode: 'random', problemId: 'vector-addition' }, cookie: outsider })).status).toBe(400);
    expect((await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda', problemId: 'guess-vector' }, cookie: owner })).status).toBe(400);
  });

  it('uses selected task difficulty for one, two or three hour duels', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const bank = (await request('/duels/problems', { cookie: owner })).body;
    expect(bank.every(item => Object.keys(item).sort().join(',') === 'difficulty,id,title')).toBe(true);
    expect(bank.some(item => item.id === 'guess-vector')).toBe(false);
    expect((await request('/duels/problems')).status).toBe(401);
    for (const difficulty of ['easy', 'medium', 'hard']) {
      const task = bank.find(item => item.difficulty === difficulty);
      expect(task).toBeDefined();
      const invitation = (await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda', problemId: task.id }, cookie: owner })).body;
      const accepted = (await request(`/duels/${invitation.id}/accept`, { method: 'POST', cookie: opponent })).body;
      const room = (await request(`/contests/${accepted.contestId}`, { cookie: owner })).body;
      expect(room.problemIds).toEqual([task.id]);
      expect(Date.parse(room.endAt) - Date.parse(room.startAt)).toBe(({ easy: 1, medium: 2, hard: 3 })[difficulty] * 3600000);
    }
  });

  it('ranks the fastest accepted code regardless of first acceptance and ends immediately on surrender', async () => {
    const owner = await login('alex_cuda');
    const opponent = await login('new_cuda');
    const outsider = await login('author_cuda');
    const invitation = (await request('/duels', { method: 'POST', body: { mode: 'invite', opponentHandle: 'new_cuda', problemId: 'vector-addition' }, cookie: owner })).body;
    const duel = (await request(`/duels/${invitation.id}/accept`, { method: 'POST', cookie: opponent })).body;
    const submit = async (cookie, time) => {
      const submitted = await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', contestId: duel.contestId, code: `void solve() {} FIXTURE_TIME_MS=${time}` }, cookie });
      expect(submitted.status).toBe(202);
      return waitForStatus(submitted.body.id, 'accepted', cookie);
    };
    await submit(owner, 0.05);
    await submit(opponent, 0.1);
    const initial = (await request(`/contests/${duel.contestId}/standings`)).body;
    expect(initial[0]).toMatchObject({ rank: 1, user: { id: 'alex_cuda' }, bestTimeMs: 0.05 });
    await submit(opponent, 0.01);
    const improved = (await request(`/contests/${duel.contestId}/standings`)).body;
    expect(improved[0]).toMatchObject({ rank: 1, user: { id: 'new_cuda' }, bestTimeMs: 0.01 });
    expect((await request(`/contests/${duel.contestId}/surrender`, { method: 'POST', cookie: outsider })).status).toBe(403);
    expect((await request(`/contests/${duel.contestId}/surrender`, { method: 'POST', cookie: opponent })).status).toBe(204);
    expect((await request(`/contests/${duel.contestId}/surrender`, { method: 'POST', cookie: opponent })).status).toBe(204);
    const ended = (await request(`/contests/${duel.contestId}`, { cookie: owner })).body;
    expect(ended.surrenderedUserId).toBe('new_cuda');
    expect(Date.parse(ended.endAt)).toBeLessThanOrEqual(Date.now());
    const final = (await request(`/contests/${duel.contestId}/standings`)).body;
    expect(final[0]).toMatchObject({ rank: 1, user: { id: 'alex_cuda' }, surrendered: false });
    expect(final[1]).toMatchObject({ rank: 2, user: { id: 'new_cuda' }, surrendered: true });
    expect((await request('/duels?phase=active', { cookie: owner })).body.total).toBe(0);
    expect((await request('/duels?phase=finished', { cookie: owner })).body).toMatchObject({ total: 1, items: [{ contestId: duel.contestId, surrenderedUserId: 'new_cuda' }] });
    expect((await request('/notifications', { cookie: owner })).body.items[0]).toMatchObject({ type: 'duel_surrendered', contestId: duel.contestId });
    expect((await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', contestId: duel.contestId, code: 'void solve() {}' }, cookie: owner })).status).toBe(400);
  });

  it('publishes a comparison from two own accepted submissions without publishing measurements', async () => {
    const cookie = await login('alex_cuda');
    const queued = await request('/submissions', { method: 'POST', body: { problemId: 'vector-addition', code: 'void solve() {}' }, cookie });
    await waitForStatus(queued.body.id, 'accepted', cookie);
    const created = await request('/problems/vector-addition/comparisons', { method: 'POST', body: { firstSubmissionId: '1042', secondSubmissionId: queued.body.id, explanation: 'Разные размеры блока.' }, cookie });
    expect(created.status).toBe(201);
    expect(created.body.format).toBe('guess');
    const detail = (await request(`/problems/${created.body.id}`)).body;
    expect(detail.firstCode).toContain('void solve');
    expect(detail.secondCode).toBe('void solve() {}');
    expect(detail).not.toHaveProperty('firstTimeMs');
    expect(detail).not.toHaveProperty('explanation');
    const other = await login('new_cuda');
    expect((await request('/problems/vector-addition/comparisons', { method: 'POST', body: { firstSubmissionId: '1042', secondSubmissionId: queued.body.id, explanation: 'Чужие посылки.' }, cookie: other })).status).toBe(400);
  });
});
