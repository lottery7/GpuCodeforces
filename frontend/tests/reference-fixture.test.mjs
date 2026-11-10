import { once } from 'node:events';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createApiServer } from './fixtures/api-server.mjs';

let server;
let base;
let cookie;
let references;

async function request(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  return { status: response.status, body: response.status === 204 ? null : await response.json() };
}

beforeEach(async () => {
  references = new Map();
  server = createApiServer({ referenceMeasurements: references });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  base = `http://127.0.0.1:${server.address().port}/api`;
  const login = await fetch(`${base}/session`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ identifier: 'alex_cuda', password: 'password123' }) });
  cookie = login.headers.get('set-cookie').split(';')[0];
});

afterEach(async () => {
  server.closeAllConnections();
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

describe('Reference measurement HTTP API', () => {
  it('returns ready reference metadata by default without revealing the comparison answer', async () => {
    const optimization = (await request('/problems/optimize-vector')).body;
    expect(optimization.referenceMeasurement).toMatchObject({ status: 'ready', diagnostic: null });
    expect(optimization.referenceMeasurement.key).toBeTruthy();
    expect(optimization.baselineTimeMs).toBeGreaterThan(0);
    const guess = (await request('/problems/guess-vector')).body;
    expect(guess.referenceMeasurement.status).toBe('ready');
    expect(guess.firstCode).toContain('void solve');
    expect(guess).not.toHaveProperty('firstTimeMs');
    expect(guess).not.toHaveProperty('secondTimeMs');
    expect(guess).not.toHaveProperty('correctChoice');
  });

  it('hides stale baseline values and rejects guessing while references are pending or failed', async () => {
    references.set('optimize-vector', { key: 'new-environment', status: 'running', baselineTimeMs: 999 });
    const optimization = (await request('/problems/optimize-vector')).body;
    expect(optimization.baselineTimeMs).toBeNull();
    expect(optimization.referenceMeasurement.status).toBe('running');
    references.set('optimize-vector', { key: 'new-environment', status: 'ready', baselineTimeMs: 0.345 });
    expect((await request('/problems/optimize-vector')).body.baselineTimeMs).toBe(0.345);
    for (const status of ['pending', 'failed']) {
      references.set('guess-vector', { key: 'new-environment', status, diagnostic: status === 'failed' ? 'Замер недоступен.' : null });
      const detail = (await request('/problems/guess-vector')).body;
      expect(detail.firstCode).toContain('void solve');
      expect(detail.guessSubmissionId).toBeNull();
      expect(detail.referenceMeasurement.status).toBe(status);
      expect((await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: 'new-environment' } })).status).toBe(503);
    }
    expect((await request('/submissions?problemId=guess-vector')).body.total).toBe(0);
    references.set('guess-vector', { key: 'new-environment', status: 'ready', firstTimeMs: 0.01, secondTimeMs: 0.02 });
    const saved = await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: 'new-environment' } });
    expect(saved.status).toBe(200);
    expect(saved.body.guessResult).toMatchObject({ benchmarkKey: 'new-environment', firstTimeMs: 0.01, secondTimeMs: 0.02 });
  });

  it('opens a new attempt for a new environment while keeping old history times unchanged', async () => {
    const key = (await request('/problems/guess-vector')).body.referenceMeasurement.key;
    const first = (await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: key } })).body;
    expect(first.guessResult.synthetic).toBe(true);
    references.set('guess-vector', { key: 'environment-b', status: 'pending' });
    expect((await request('/problems/guess-vector')).body.guessSubmissionId).toBeNull();
    const oldDuringMeasurement = (await request(`/submissions/${first.id}`)).body;
    expect(oldDuringMeasurement.guessResult).toEqual(first.guessResult);
    references.set('guess-vector', { key: 'environment-b', status: 'ready', firstTimeMs: 2, secondTimeMs: 1 });
    const second = (await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'second', benchmarkKey: 'environment-b' } })).body;
    expect(second.id).not.toBe(first.id);
    expect(second.guessResult).toMatchObject({ correctChoice: 'second', benchmarkKey: 'environment-b', firstTimeMs: 2, secondTimeMs: 1 });
    expect((await request('/problems/guess-vector')).body.guessSubmissionId).toBe(second.id);
    expect((await request(`/submissions/${first.id}`)).body.guessResult).toEqual(first.guessResult);
  });

  it('rejects an outdated client key without creating an attempt', async () => {
    const key = (await request('/problems/guess-vector')).body.referenceMeasurement.key;
    references.set('guess-vector', { key: 'environment-b', status: 'ready', firstTimeMs: 2, secondTimeMs: 1 });
    const rejected = await request('/problems/guess-vector/guess', { method: 'POST', body: { choice: 'first', benchmarkKey: key } });
    expect(rejected.status).toBe(400);
    expect(rejected.body.message).toContain('Условия сравнения изменились');
    expect((await request('/problems/guess-vector')).body.guessSubmissionId).toBeNull();
    expect((await request('/submissions?problemId=guess-vector')).body.total).toBe(0);
  });
});
