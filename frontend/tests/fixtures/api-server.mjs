import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import vm from 'node:vm';

const modelSource = readFileSync(new URL('../../../docs/frontend/mockups/model.js', import.meta.url), 'utf8');
const wireStatuses = {
  queued: 'queued', compiling: 'compiling', checking: 'checking', measuring: 'measuring',
  accepted: 'accepted', compile: 'compile_error', wrong: 'wrong_answer', runtime: 'runtime_error',
  timeout: 'time_limit', memory: 'memory_limit', system: 'system_error', 'measurement-error': 'measurement_error',
};
const modelStatuses = Object.fromEntries(Object.entries(wireStatuses).map(([key, value]) => [value, key]));
const diagnostics = {
  compile: 'solution.cu: ошибка компиляции тестового решения.',
  wrong: 'Ответ не совпадает с ожидаемым на тесте 7.',
  runtime: 'Ошибка выполнения CUDA: выход за границы массива.',
  timeout: 'Превышен лимит времени выполнения.',
  memory: 'Превышен лимит памяти GPU.',
  system: 'Тестовый временный сбой проверяющей системы. Повторите проверку.',
  'measurement-error': 'Корректность подтверждена, но замер времени завершился сбоем.',
};
const longDiagnostic = 'solution.cu(42): error: identifier "invalid_index" is undefined\n    output[invalid_index] = input[invalid_index];\n           ^\n'.repeat(160).slice(0, 16 * 1024);

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function memoryStorage() {
  const items = new Map();
  return {
    getItem: key => items.get(key) ?? null,
    setItem: (key, value) => items.set(key, String(value)),
    removeItem: key => items.delete(key),
  };
}

function makeModel(storage = memoryStorage()) {
  const context = vm.createContext({
    window: {}, localStorage: storage, sessionStorage: memoryStorage(),
  });
  vm.runInContext(modelSource, context);
  return context.window.AppModel;
}

const passwordHash = value => createHash('sha256').update(value).digest('hex');
const truth = value => value === 'true';

function queryInteger(query, key, fallback, max = Number.MAX_SAFE_INTEGER) {
  const raw = query.get(key);
  if (raw === null) return fallback;
  if (!/^\d+$/.test(raw)) throw new ApiError(400, `Некорректный параметр ${key}.`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value > max || key === 'limit' && value < 1) {
    throw new ApiError(400, `Некорректный параметр ${key}.`);
  }
  return value;
}

function page(items, query) {
  const offset = queryInteger(query, 'offset', 0);
  const limit = queryInteger(query, 'limit', 20, 100);
  return { items: items.slice(offset, offset + limit), total: items.length };
}

function textBody(body, key, allowEmpty = false) {
  if (typeof body[key] !== 'string' || !allowEmpty && !body[key].trim()) {
    throw new ApiError(400, `Заполните поле ${key}.`);
  }
  return body[key];
}

async function readBody(request) {
  if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) {
    throw new ApiError(400, 'Ожидается application/json.');
  }
  let content = '';
  for await (const chunk of request) {
    content += chunk.toString('utf8');
    if (Buffer.byteLength(content) > 2 * 1024 * 1024) throw new ApiError(400, 'Слишком большой запрос.');
  }
  let parsed;
  try {
    parsed = JSON.parse(content);
  } catch {
    throw new ApiError(400, 'Некорректный JSON.');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new ApiError(400, 'Ожидается объект JSON.');
  return parsed;
}

function environmentMarkdown(benchmark) {
  return [
    '| Параметр | Значение |', '| --- | --- |',
    `| GPU | ${benchmark.gpu} |`,
    `| Компилятор | ${benchmark.compiler} |`,
    `| Размер входа | ${benchmark.caseLabel} |`,
    `| Прогрев | ${benchmark.warmups} запусков |`,
    `| Измерение | ${benchmark.runs} запусков, ${benchmark.method.toLowerCase()} |`,
    `| Эталон | ${benchmark.referenceMs} мс |`,
    `| Версия проверки | ${benchmark.testVersion} |`,
    '', 'Передачи между CPU и GPU не входят в измеряемое время.',
  ].join('\n');
}

function outcomeFor(code) {
  if (code.includes('FIXTURE_LONG_DIAGNOSTIC')) return 'system';
  for (const [wire, model] of Object.entries(modelStatuses)) {
    if (['queued', 'compiling', 'checking', 'measuring'].includes(wire)) continue;
    if (code.includes(`FIXTURE_${wire.toUpperCase()}`)) return model;
  }
  return 'accepted';
}

export function createApiServer() {
  const storage = memoryStorage();
  let model = makeModel(storage);
  function mutateModel(update) {
    const userId = model.user()?.id ?? null;
    const state = JSON.parse(storage.getItem('gpu-forces-prototype-v3'));
    update(state);
    storage.setItem('gpu-forces-prototype-v3', JSON.stringify(state));
    model = makeModel(storage);
    model.setSession(userId);
  }
  const source = model.submission(1042).code;
  mutateModel(state => {
    state.preparedProblems.push({ ...model.problem('vector-addition'), id: 'optimize-vector', code: '010', title: 'Оптимизируйте сложение векторов', format: 'optimization', starter: source.replaceAll('255', '127').replaceAll('256', '128'), baselineTimeMs: 0.08 });
    state.preparedProblems.push({ ...model.problem('vector-addition'), id: 'guess-vector', code: '011', title: 'Какое сложение быстрее?', format: 'guess', firstCode: source, secondCode: source.replaceAll('255', '127').replaceAll('256', '128'), firstTimeMs: 0.052, secondTimeMs: 0.08, explanation: 'В этом замере вариант с блоком из 256 потоков быстрее.' });
  });
  const sessions = new Map();
  const passwords = new Map(model.users().map(user => [user.id, passwordHash('password123')]));
  const timers = new Set();
  const duelRequests = new Map();
  const notifications = [];

  function expireDuels() {
    for (const duel of duelRequests.values()) {
      if (['queued', 'invited'].includes(duel.status) && Date.parse(duel.expiresAt) <= Date.now()) duel.status = 'expired';
    }
  }

  function activeDuel(userId) {
    expireDuels();
    return [...duelRequests.values()].find(item => Date.parse(item.expiresAt) > Date.now() && (['queued', 'invited'].includes(item.status) && item.creatorId === userId || item.status === 'matched' && [item.creatorId, item.opponentId].includes(userId)));
  }

  function duelDto(duel) {
    return { id: duel.id, mode: duel.mode, status: duel.status, creator: publicUser(userById(duel.creatorId)), opponent: duel.opponentId ? publicUser(userById(duel.opponentId)) : null, topics: duel.topics, createdAt: duel.createdAt, expiresAt: duel.expiresAt, contestId: duel.contestId };
  }

  function visibleDuel(id, user) {
    expireDuels();
    const duel = duelRequests.get(id);
    if (!duel) throw new ApiError(404, 'Дуэль не найдена.');
    if (![duel.creatorId, duel.opponentId].includes(user.id)) throw new ApiError(403, 'Дуэль доступна только ее участникам.');
    return duel;
  }

  function notify(userId, type, message, duel) {
    notifications.unshift({ id: randomUUID(), userId, type, message, duelRequestId: duel.id, contestId: duel.contestId, createdAt: new Date().toISOString(), readAt: null });
  }

  function cancelWaiting(duel) {
    duel.status = 'cancelled';
    if (duel.mode === 'invite') notify(duel.opponentId, 'duel_cancelled', 'Приглашение на дуэль отменено.', duel);
  }

  function codingPool(topics = []) {
    return model.problems().filter(problem => problem.format !== 'guess' && (!topics.length || problem.tags.some(tag => topics.includes(tag))));
  }

  function startDuel(firstId, secondId, candidates) {
    const problem = candidates[Math.floor(Math.random() * candidates.length)];
    const start = Date.now();
    const contest = { id: `duel-${randomUUID()}`, title: `Дуэль: ${userById(firstId).handle} vs ${userById(secondId).handle}`, authorIds: [firstId], ownerId: firstId, format: 'duel', challengerId: firstId, opponentId: secondId, startAt: new Date(start).toISOString(), endAt: new Date(start + 30 * 60000).toISOString(), durationMinutes: 30, registrationOpen: true, gpuId: 'a100', scoring: 'solve-time', problemIds: [problem.id], benchmarks: { [problem.id]: problem.benchmark }, problemSnapshots: { [problem.id]: problem } };
    mutateModel(state => {
      state.contests.push(contest);
      state.registrations.push(...[firstId, secondId].map(userId => ({ userId, contestId: contest.id })));
    });
    return contest;
  }

  function matched(duel, opponentId, contest) {
    Object.assign(duel, { status: 'matched', opponentId, contestId: contest.id, expiresAt: contest.endAt });
  }

  function publicUser(user) {
    if (!user) return null;
    const ranked = model.users().filter(person => person.rating !== null)
      .sort((a, b) => b.rating - a.rating || a.handle.localeCompare(b.handle));
    return {
      id: user.id, handle: user.handle, rating: user.rating,
      rank: user.rating === null ? null : ranked.findIndex(person => person.rating === user.rating) + 1,
      ratingDelta: user.ratingDelta, ratingContestId: user.ratingContestId,
    };
  }

  function userById(id) {
    const user = model.users().find(person => person.id === id);
    if (!user) throw new ApiError(404, 'Участник не найден.');
    return user;
  }

  function requireUser() {
    const user = model.user();
    if (!user) throw new ApiError(401, 'Войдите в аккаунт.');
    return user;
  }

  function contestById(id) {
    const contest = model.contest(id);
    if (!contest) throw new ApiError(404, 'Контест не найден.');
    return contest;
  }

  function resolveContext(context = 'practice') {
    if (context === 'practice') return null;
    if (!context || context === 'all') throw new ApiError(400, 'Некорректный контекст задачи.');
    contestById(context);
    if (model.phase(context) === 'upcoming' && !model.isContestAuthor(context)) {
      throw new ApiError(403, 'Задачи откроются после начала контеста.');
    }
    return context;
  }

  function problemById(id, contestId = null) {
    const problem = model.problem(id, contestId);
    if (!problem || problem.visibility !== 'public') throw new ApiError(404, 'Задача не найдена.');
    return problem;
  }

  function problemDto(problem, contestId, targetId) {
    const last = targetId ? model.submissions({ problemId: problem.id, userId: targetId, contestId })[0] : null;
    const best = targetId ? problem.format === 'guess' ? model.submissions({ problemId: problem.id, userId: targetId, ...(contestId ? { contestId } : {}) }).find(item => item.status === 'accepted') : model.best(problem.id, { userId: targetId, contestId }) : null;
    return {
      id: problem.id, code: problem.code, title: problem.title,
      format: ['optimization', 'guess'].includes(problem.format) ? problem.format : 'standard', baselineTimeMs: problem.baselineTimeMs ?? null,
      difficulty: problem.difficulty, tags: problem.tags,
      solved: targetId ? model.solved(problem.id, targetId, contestId) : false,
      solvedCount: model.users().filter(user => model.solved(problem.id, user.id, contestId)).length,
      lastSubmissionId: last ? String(last.id) : null, lastStatus: last ? wireStatuses[last.status] : null,
      bestSubmissionId: best ? String(best.id) : null, bestTimeMs: best?.timeMs ?? null,
      recordTimeMs: model.leaderboard(problem.id, contestId)[0]?.submission.timeMs ?? null,
    };
  }

  function statementMarkdown(problem) {
    const tolerance = problem.tolerance.startsWith('abs(')
      ? 'Абсолютный допуск: 1e-5, относительный: 1e-5.'
      : problem.tolerance;
    return [
      problem.statement, '', '## Контракт', problem.contract,
      '', '```cpp', `${problem.signature};`, '```',
      '', '## Пример', '```text', problem.example, '```',
      '', '## Ограничения', problem.limits.input,
      '', '## Проверка ответа', tolerance,
    ].join('\n');
  }

  function rulesMarkdown(contest) {
    const general = [
      'Решения принимаются на C++/CUDA и проверяются на корректность в пределах лимита времени.',
      '', `Все задачи контеста проверяются на ${model.gpuProfiles().find(gpu => gpu.id === contest.gpuId)?.label ?? 'NVIDIA A100 80 GB'}.`,
      '', 'Сначала сравнивается число решенных задач, затем меньшее суммарное время сдачи: для каждой решенной задачи время от начала контеста до отправки первой принятой посылки. Время работы на GPU и неверные попытки не учитываются. Одинаковые результаты получают одинаковое место.',
      '', 'Конкурсная отправка доступна зарегистрированным участникам во время соревнования. После завершения задачи можно решать в практике без изменения результатов контеста.',
    ];
    if (model.phase(contest.id) === 'upcoming' && !model.isContestAuthor(contest.id)) return general.join('\n');
    return [...general, '', '## Задачи',
      ...contest.problemIds.map((id, index) => `- ${String.fromCharCode(65 + index)}. ${problemById(id, contest.id).title}`),
    ].join('\n');
  }

  /** Same rules as the backend: solved problems, then the total time from the start to the first accepted submission. */
  function standings(contest) {
    return model.standings(contest.id).map(row => ({
      rank: row.rank, user: publicUser(row.user), solved: row.solved, totalSeconds: row.totalSeconds, times: row.times,
    }));
  }

  function contestDto(contest, detail = false) {
    const hidden = model.phase(contest.id) === 'upcoming' && !model.isContestAuthor(contest.id);
    return {
      id: contest.id, title: contest.title, startAt: contest.startAt, endAt: contest.endAt,
      format: contest.format ?? 'standard', challengerId: contest.challengerId ?? null, opponentId: contest.opponentId ?? null,
      authors: contest.authorIds.map(id => publicUser(userById(id))),
      problemIds: hidden ? [] : contest.problemIds, problemCount: contest.problemIds.length,
      registered: model.joined(contest.id),
      gpu: model.gpuProfiles().find(profile => profile.id === contest.gpuId)?.label ?? 'NVIDIA A100 80 GB',
      ...(detail ? { rulesMarkdown: rulesMarkdown(contest) } : {}),
    };
  }

  function submissionDto(submission, detail = false) {
    const problem = problemById(submission.problemId, submission.contestId);
    const owner = model.user()?.id === submission.userId;
    const previous = Object.hasOwn(submission, 'previousBestMs') ? submission.previousBestMs : model.submissions({
      userId: submission.userId, problemId: submission.problemId, contestId: submission.contestId,
    }).filter(item => Date.parse(item.createdAt) < Date.parse(submission.createdAt) && model.canCompare(item))
      .sort((a, b) => a.timeMs - b.timeMs)[0]?.timeMs ?? null;
    const benchmark = submission.benchmarkSnapshot ?? submission.benchmark;
    const diagnostic = typeof submission.diagnostic === 'string' ? submission.diagnostic
      : submission.diagnostic?.message ?? '';
    return {
      id: String(submission.id), problemId: problem.id, problemTitle: problem.title,
      format: ['optimization', 'guess'].includes(problem.format) ? problem.format : 'standard',
      user: publicUser(userById(submission.userId)), contestId: submission.contestId,
      contestTitle: submission.contestId ? contestById(submission.contestId).title : null,
      createdAt: submission.createdAt, status: wireStatuses[submission.status],
      timeMs: submission.status === 'accepted' ? submission.timeMs : null,
      comparable: problem.format === 'guess' ? true : model.canCompare(submission),
      ...(detail ? {
        passedTests: submission.passedTests, totalTests: submission.totalTests,
        measurement: { aggregation: 'geometric_mean', timingMode: 'wall', gpuName: null, synthetic: true, testCount: 1 },
        previousBestTimeMs: previous, environmentMarkdown: environmentMarkdown(benchmark),
        ...(owner ? problem.format === 'guess' ? { guessResult: submission.guessResult } : { code: submission.code, diagnostic } : {}),
      } : {}),
    };
  }

  function enqueue(id, outcome) {
    const states = ['compiling', 'checking', 'measuring', outcome];
    states.forEach((status, index) => {
      const timer = setTimeout(() => {
        timers.delete(timer);
        const submission = model.submission(id);
        if (!submission) return;
        const benchmark = submission.benchmarkSnapshot ?? submission.benchmark;
        model.updateSubmission(id, {
          status, timeMs: status === 'accepted' ? Number((benchmark.referenceMs * 0.8).toFixed(4)) : null,
          diagnostic: status === 'system' && submission.code.includes('FIXTURE_LONG_DIAGNOSTIC') ? longDiagnostic : diagnostics[status] ?? null,
          passedTests: ['accepted', 'measurement-error', 'measuring'].includes(status) ? submission.totalTests : status === 'wrong' ? 6 : 0,
        });
      }, 350 * (index + 1));
      timer.unref();
      timers.add(timer);
    });
  }

  function targetUser(query) {
    const id = query.get('userId');
    return id !== null ? userById(id).id : model.user()?.id ?? null;
  }

  function validateContestInput(body) {
    textBody(body, 'title');
    textBody(body, 'startAt');
    textBody(body, 'endAt');
    const start = Date.parse(body.startAt);
    const end = Date.parse(body.endAt);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new ApiError(400, 'Проверьте даты контеста.');
    for (const key of ['authorIds', 'problemIds']) {
      if (!Array.isArray(body[key]) || !body[key].length || body[key].some(id => typeof id !== 'string') || new Set(body[key]).size !== body[key].length) {
        throw new ApiError(400, `Выберите ${key === 'authorIds' ? 'хотя бы одного автора' : 'хотя бы одну задачу'}.`);
      }
    }
    for (const id of body.problemIds) problemById(id);
    const format = body.format ?? 'standard';
    if (format !== 'standard' || body.opponentId) throw new ApiError(400, 'Для дуэли используйте подбор соперника.');
    return { ...body, format, durationMinutes: (end - start) / 60000, gpuId: 'a100' };
  }

  function send(response, status, data, cookie) {
    response.setHeader('Cache-Control', 'no-store');
    if (cookie) response.setHeader('Set-Cookie', cookie);
    response.statusCode = status;
    if (status === 204) return response.end();
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.end(JSON.stringify(data));
  }

  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://127.0.0.1');
      const segments = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
      if (segments.shift() !== 'api') throw new ApiError(404, 'Ресурс не найден.');
      const [resource, id, action] = segments;
      const method = request.method;
      const query = url.searchParams;
      const sessionToken = /(?:^|;\s*)session=([^;]+)/.exec(request.headers.cookie ?? '')?.[1];
      const needsBody = method === 'POST' && (resource === 'session' || resource === 'users' || resource === 'duels' && !id || resource === 'submissions' && !id || resource === 'contests' && !id || resource === 'problems' && ['example', 'guess', 'comparisons'].includes(action))
        || method === 'PUT' && resource === 'contests' && id && !action;
      const body = needsBody ? await readBody(request) : {};
      model.setSession(sessions.get(sessionToken) ?? null);

      if (resource === 'runtime' && method === 'GET') return send(response, 200, { executionMode: 'mock', gpuName: null });

      if (resource === 'duels') {
        expireDuels();
        if (method === 'GET' && id === 'topics') return send(response, 200, [...new Set(codingPool().flatMap(problem => problem.tags))].sort());
        const user = requireUser();
        if (method === 'GET' && id === 'current') {
          const active = activeDuel(user.id);
          return send(response, active ? 200 : 204, active ? duelDto(active) : null);
        }
        if (method === 'POST' && !id) {
          if (!['random', 'invite'].includes(body.mode)) throw new ApiError(400, 'Выберите способ поиска соперника.');
          if (activeDuel(user.id)) throw new ApiError(400, 'Сначала завершите текущую дуэль или отмените поиск.');
          const known = new Map(codingPool().flatMap(problem => problem.tags).map(tag => [tag.toLowerCase(), tag]));
          if (!Array.isArray(body.topics) || body.topics.length > 20 || body.topics.some(topic => typeof topic !== 'string' || !known.has(topic.trim().toLowerCase()))) throw new ApiError(400, 'Выберите доступные темы задач.');
          const topics = [...new Set(body.topics.map(topic => known.get(topic.trim().toLowerCase())))];
          const candidates = codingPool(topics);
          if (!candidates.length) throw new ApiError(400, 'По выбранным темам нет задач.');
          const opponent = body.mode === 'invite' ? model.users().find(person => person.handle.toLowerCase() === textBody(body, 'opponentHandle').trim().toLowerCase()) : null;
          if (body.mode === 'invite' && (!opponent || opponent.id === user.id)) throw new ApiError(400, 'Укажите ник другого участника.');
          if (body.mode === 'random' && body.opponentHandle !== undefined) throw new ApiError(400, 'Ник не нужен для случайного поиска.');
          const now = Date.now();
          const duel = { id: randomUUID(), mode: body.mode, status: body.mode === 'random' ? 'queued' : 'invited', creatorId: user.id, opponentId: opponent?.id ?? null, topics, createdAt: new Date(now).toISOString(), expiresAt: new Date(now + (body.mode === 'random' ? 15 * 60000 : 24 * 3600000)).toISOString(), contestId: null };
          duelRequests.set(duel.id, duel);
          if (opponent) notify(opponent.id, 'duel_invitation', `${user.handle} приглашает вас на дуэль.`, duel);
          else {
            const peer = [...duelRequests.values()].find(item => item.id !== duel.id && item.mode === 'random' && item.status === 'queued' && candidates.some(problem => !item.topics.length || problem.tags.some(tag => item.topics.includes(tag))));
            if (peer) {
              const common = candidates.filter(problem => !peer.topics.length || problem.tags.some(tag => peer.topics.includes(tag)));
              const contest = startDuel(peer.creatorId, user.id, common);
              matched(peer, user.id, contest); matched(duel, peer.creatorId, contest);
              notify(peer.creatorId, 'duel_started', 'Соперник найден. Дуэль началась.', peer);
              notify(user.id, 'duel_started', 'Соперник найден. Дуэль началась.', duel);
            }
          }
          return send(response, 201, duelDto(duel));
        }
        if (id) {
          const duel = visibleDuel(id, user);
          if (method === 'GET' && !action) return send(response, 200, duelDto(duel));
          if (method === 'POST' && action === 'cancel') {
            if (duel.creatorId !== user.id) throw new ApiError(403, 'Отменить поиск может только его автор.');
            if (duel.status !== 'cancelled' && !['queued', 'invited'].includes(duel.status)) throw new ApiError(400, 'Этот поиск уже завершен.');
            if (duel.status !== 'cancelled') cancelWaiting(duel);
            return send(response, 200, duelDto(duel));
          }
          if (method === 'POST' && ['accept', 'decline'].includes(action)) {
            if (duel.mode !== 'invite' || duel.opponentId !== user.id) throw new ApiError(403, 'Ответить на приглашение может только соперник.');
            if (action === 'accept' && duel.status === 'matched' || action === 'decline' && duel.status === 'declined') return send(response, 200, duelDto(duel));
            if (duel.status !== 'invited') throw new ApiError(400, 'Приглашение уже недоступно.');
            if (action === 'decline') {
              duel.status = 'declined';
              notify(duel.creatorId, 'duel_declined', `${user.handle} отклонил приглашение.`, duel);
            } else {
              const active = activeDuel(user.id);
              if (active?.status === 'matched') throw new ApiError(400, 'Сначала завершите текущую дуэль.');
              if (active) cancelWaiting(active);
              const candidates = codingPool(duel.topics);
              if (!candidates.length) throw new ApiError(400, 'По выбранным темам нет задач.');
              const contest = startDuel(duel.creatorId, user.id, candidates);
              matched(duel, user.id, contest);
              notify(duel.creatorId, 'duel_started', `${user.handle} принял приглашение. Дуэль началась.`, duel);
              notify(user.id, 'duel_started', 'Вы приняли приглашение. Дуэль началась.', duel);
            }
            return send(response, 200, duelDto(duel));
          }
        }
      }

      if (resource === 'notifications') {
        const user = requireUser();
        expireDuels();
        if (method === 'GET' && !id) {
          const own = notifications.filter(item => item.userId === user.id);
          const items = own.map(({ userId, ...item }) => ({ ...item, duelStatus: duelRequests.get(item.duelRequestId)?.status, contestId: duelRequests.get(item.duelRequestId)?.contestId ?? item.contestId }));
          return send(response, 200, { ...page(items, query), unreadCount: own.filter(item => !item.readAt).length });
        }
        if (method === 'PUT' && action === 'read') {
          const item = notifications.find(item => item.id === id && item.userId === user.id);
          if (!item) throw new ApiError(404, 'Уведомление не найдено.');
          item.readAt ??= new Date().toISOString();
          return send(response, 204);
        }
      }

      if (resource === 'session' && segments.length === 1) {
        if (method === 'GET') return send(response, 200, publicUser(requireUser()));
        if (method === 'POST') {
          const identifier = textBody(body, 'identifier');
          const password = textBody(body, 'password', true);
          const user = model.findUser(identifier);
          if (!user || passwords.get(user.id) !== passwordHash(password)) throw new ApiError(401, 'Неверный ник или пароль.');
          const token = randomUUID();
          sessions.set(token, user.id);
          model.setSession(user.id);
          return send(response, 200, publicUser(user), `session=${token}; Path=/; HttpOnly; SameSite=Lax`);
        }
        if (method === 'DELETE') {
          requireUser();
          sessions.delete(sessionToken);
          model.setSession(null);
          return send(response, 204, null, 'session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0');
        }
      }

      if (resource === 'users' && segments.length === 1) {
        if (method === 'GET') {
          const sort = query.get('sort') ?? 'handle';
          if (!['handle', 'rating'].includes(sort)) throw new ApiError(400, 'Неизвестная сортировка.');
          const q = (query.get('q') ?? '').trim().toLowerCase();
          const users = model.users().map(publicUser).filter(user => (sort !== 'rating' || user.rating !== null) && user.handle.toLowerCase().includes(q));
          users.sort((a, b) => sort === 'rating' ? b.rating - a.rating || a.handle.localeCompare(b.handle) : a.handle.localeCompare(b.handle));
          return send(response, 200, page(users, query));
        }
        if (method === 'POST') {
          const password = textBody(body, 'password');
          if (password.length < 8) throw new ApiError(400, 'Пароль должен содержать не меньше 8 символов.');
          const result = model.register({ handle: textBody(body, 'handle'), email: textBody(body, 'email') });
          if (!result.ok) throw new ApiError(400, result.error);
          passwords.set(result.user.id, passwordHash(password));
          const token = randomUUID();
          sessions.set(token, result.user.id);
          return send(response, 201, publicUser(result.user), `session=${token}; Path=/; HttpOnly; SameSite=Lax`);
        }
      }

      if (resource === 'users' && segments.length === 2 && method === 'GET') {
        const user = userById(id);
        const profile = model.profile(id);
        const own = model.submissions({ userId: id });
        const contests = model.contests().filter(contest => model.joined(contest.id, id) || own.some(item => item.contestId === contest.id))
          .sort((a, b) => Date.parse(b.startAt) - Date.parse(a.startAt)).map(contest => {
            const historical = profile.contestHistory.find(item => item.contestId === contest.id);
            return {
              id: contest.id, title: contest.title, phase: model.phase(contest.id),
              rank: model.standings(contest.id).find(row => row.user.id === id)?.rank ?? null,
              ratingDelta: historical?.ratingDelta ?? null, ratingAfter: historical?.ratingAfter ?? null,
            };
          });
        return send(response, 200, { user: publicUser(user), solvedCount: profile.solved, contests });
      }

      if (resource === 'problems' && method === 'GET' && segments.length === 1) {
        const contestId = resolveContext(query.get('context') ?? 'practice');
        const target = targetUser(query);
        const all = contestId ? contestById(contestId).problemIds.map(problemId => problemById(problemId, contestId)) : model.problems();
        const tags = [...new Set(all.flatMap(problem => problem.tags))].sort((a, b) => a.localeCompare(b));
        const q = (query.get('q') ?? '').trim().toLowerCase();
        const difficulty = query.get('difficulty');
        const format = query.get('format');
        if (format !== null && !['standard', 'optimization', 'guess'].includes(format)) throw new ApiError(400, 'Неизвестный формат задачи.');
        const solved = query.get('solved');
        if (difficulty !== null && !['easy', 'medium', 'hard'].includes(difficulty)) throw new ApiError(400, 'Неизвестная сложность.');
        if (solved !== null && !['true', 'false'].includes(solved)) throw new ApiError(400, 'Некорректный фильтр решения.');
        let items = all.map(problem => problemDto(problem, contestId, target)).filter(problem =>
          `${problem.code} ${problem.title}`.toLowerCase().includes(q)
          && (difficulty === null || problem.difficulty === difficulty)
          && (format === null || problem.format === format)
          && (!query.has('tag') || problem.tags.includes(query.get('tag')))
          && (solved === null || problem.solved === truth(solved)));
        const sort = query.get('sort') ?? 'code';
        if (!['code', 'difficulty', 'bestTime'].includes(sort)) throw new ApiError(400, 'Неизвестная сортировка.');
        if (!contestId) {
          const order = { easy: 0, medium: 1, hard: 2 };
          items.sort((a, b) => (sort === 'difficulty' ? order[a.difficulty] - order[b.difficulty] : sort === 'bestTime' ? (a.bestTimeMs ?? Infinity) - (b.bestTimeMs ?? Infinity) : 0) || a.code.localeCompare(b.code));
        }
        return send(response, 200, { ...page(items, query), tags });
      }

      if (resource === 'problems' && id && segments.length <= 3) {
        const contestId = resolveContext(query.get('context') ?? 'practice');
        const problem = problemById(id, contestId);
        if (method === 'GET' && !action) {
          return send(response, 200, {
            ...problemDto(problem, contestId, model.user()?.id ?? null),
            versionId: `fixture-${problem.id}-v1`, statementMarkdown: statementMarkdown(problem), starterCode: problem.starter,
            timeLimitSeconds: problem.limits.timeSeconds, memoryLimitMb: problem.limits.memoryMb,
            environmentMarkdown: environmentMarkdown(problem.benchmark),
            benchmark: { inputDescription: problem.benchmark.caseLabel, aggregation: 'geometric_mean', timingMode: 'wall', warmupRuns: 1, maxRuns: 3, gpuName: null, synthetic: true },
            firstCode: problem.firstCode ?? null, secondCode: problem.secondCode ?? null,
            guessSubmissionId: problem.format === 'guess' && model.user() ? String(model.submissions({ userId: model.user().id, problemId: id, contestId }).find(item => item.guessResult)?.id ?? '') || null : null,
          });
        }
        if (method === 'GET' && action === 'leaders') {
          return send(response, 200, model.leaderboard(id, contestId).map(row => ({
            rank: row.rank, user: publicUser(row.user), timeMs: row.submission.timeMs,
            submissionId: String(row.submission.id),
          })));
        }
        if (method === 'POST' && action === 'example') {
          if (problem.format === 'guess') throw new ApiError(400, 'В этой задаче нужно выбрать ответ.');
          const code = textBody(body, 'code');
          const outcome = outcomeFor(code);
          return send(response, 200, { passed: outcome === 'accepted', diagnostic: code.includes('FIXTURE_LONG_DIAGNOSTIC') ? longDiagnostic : diagnostics[outcome] ?? '' });
        }
        if (method === 'POST' && action === 'guess') {
          const user = requireUser();
          if (problem.format !== 'guess' || !['first', 'second', 'equal'].includes(body.choice)) throw new ApiError(400, 'Выберите вариант ответа.');
          if (contestId && (model.phase(contestId) !== 'live' || !model.joined(contestId))) throw new ApiError(403, 'Нужно участвовать в действующем контесте.');
          const previous = model.submissions({ userId: user.id, problemId: id, contestId }).find(item => item.guessResult);
          if (previous) {
            if (previous.guessResult.choice !== body.choice) throw new ApiError(400, 'Ответ уже сохранен.');
            return send(response, 200, submissionDto(previous, true));
          }
          const correctChoice = problem.firstTimeMs === problem.secondTimeMs ? 'equal' : problem.firstTimeMs < problem.secondTimeMs ? 'first' : 'second';
          let result;
          mutateModel(state => {
            result = { id: state.nextSubmissionId++, userId: user.id, problemId: id, contestId, createdAt: new Date().toISOString(), status: body.choice === correctChoice ? 'accepted' : 'wrong', timeMs: null, benchmark: problem.benchmark, benchmarkSnapshot: problem.benchmark, passedTests: body.choice === correctChoice ? 1 : 0, totalTests: 1, testVersion: problem.benchmark.testVersion, guessResult: { choice: body.choice, correctChoice, firstTimeMs: problem.firstTimeMs, secondTimeMs: problem.secondTimeMs, explanation: problem.explanation ?? '' } };
            state.submissions.push(result);
          });
          return send(response, 200, submissionDto(result, true));
        }
        if (method === 'POST' && action === 'comparisons') {
          const user = requireUser();
          const first = model.submission(textBody(body, 'firstSubmissionId'));
          const second = model.submission(textBody(body, 'secondSubmissionId'));
          if (!first || !second || first.id === second.id || [first, second].some(item => item.userId !== user.id || item.problemId !== id || item.status !== 'accepted' || !Number.isFinite(item.timeMs) || item.timeMs <= 0 || item.contestId && model.phase(item.contestId) !== 'finished') || first.profileId !== second.profileId || first.testVersion !== second.testVersion) throw new ApiError(400, 'Выберите две свои принятые посылки одной задачи в одной конфигурации проверки.');
          const explanation = textBody(body, 'explanation').trim();
          if (explanation.length > 4000) throw new ApiError(400, 'Объяснение слишком длинное.');
          const comparison = { ...problem, id: `comparison-${randomUUID()}`, code: `C${Date.now()}`, title: `${problem.title}: сравнение решений`, format: 'guess', firstCode: first.code, secondCode: second.code, firstTimeMs: first.timeMs, secondTimeMs: second.timeMs, explanation };
          mutateModel(state => state.preparedProblems.push(comparison));
          return send(response, 201, problemDto(comparison, null, user.id));
        }
      }

      if (resource === 'submissions' && segments.length === 1) {
        if (method === 'GET') {
          const userId = targetUser(query);
          if (!userId) throw new ApiError(401, 'Войдите, чтобы посмотреть свои посылки.');
          const context = query.get('context') ?? 'all';
          if (!context) throw new ApiError(400, 'Некорректный контекст.');
          if (!['all', 'practice'].includes(context)) contestById(context);
          const status = query.get('status');
          if (status !== null && !modelStatuses[status]) throw new ApiError(400, 'Неизвестный вердикт.');
          const filters = { userId };
          if (query.has('problemId')) filters.problemId = query.get('problemId');
          if (context !== 'all') filters.contestId = context === 'practice' ? null : context;
          const items = model.submissions(filters).filter(item => status === null || item.status === modelStatuses[status]);
          return send(response, 200, page(items.map(item => submissionDto(item)), query));
        }
        if (method === 'POST') {
          requireUser();
          const problemId = textBody(body, 'problemId');
          const contestId = body.contestId === undefined ? null : textBody(body, 'contestId');
          if (problemById(problemId, contestId).format === 'guess') throw new ApiError(400, 'В этой задаче нужно выбрать ответ.');
          const code = textBody(body, 'code');
          const result = model.submit({ problemId, contestId, code });
          if (!result.ok) throw new ApiError(400, result.error);
          const dto = submissionDto(result.submission);
          enqueue(result.submission.id, outcomeFor(code));
          return send(response, 202, dto);
        }
      }

      if (resource === 'submissions' && id && segments.length <= 3) {
        const submission = model.submission(id);
        if (!submission) throw new ApiError(404, 'Посылка не найдена.');
        if (method === 'GET' && !action) return send(response, 200, submissionDto(submission, true));
        if (method === 'POST' && action === 'recheck') {
          const user = requireUser();
          if (submission.userId !== user.id) throw new ApiError(403, 'Повторная проверка доступна только автору.');
          if (!['system', 'measurement-error'].includes(submission.status)) throw new ApiError(400, 'Для этого вердикта повторная проверка недоступна.');
          const queued = model.updateSubmission(id, { status: 'queued', timeMs: null, diagnostic: null, passedTests: 0 });
          enqueue(id, 'accepted');
          return send(response, 202, submissionDto(queued));
        }
      }

      if (resource === 'contests' && segments.length === 1) {
        if (method === 'GET') {
          const phase = query.get('phase');
          const mine = query.get('mine');
          if (phase !== null && !['upcoming', 'live', 'finished'].includes(phase)) throw new ApiError(400, 'Неизвестная фаза контеста.');
          if (mine !== null && !['true', 'false'].includes(mine)) throw new ApiError(400, 'Некорректный фильтр автора.');
          if (truth(mine)) requireUser();
          const q = (query.get('q') ?? '').trim().toLowerCase();
          const items = model.contests().filter(contest => (!phase || model.phase(contest.id) === phase)
            && (!truth(mine) || model.isContestAuthor(contest.id)) && contest.title.toLowerCase().includes(q));
          items.sort((a, b) => phase === 'finished' ? Date.parse(b.startAt) - Date.parse(a.startAt) : Date.parse(a.startAt) - Date.parse(b.startAt));
          return send(response, 200, items.map(contest => contestDto(contest)));
        }
        if (method === 'POST') {
          requireUser();
          const values = validateContestInput(body);
          const result = model.saveContest(null, values);
          if (!result.ok) throw new ApiError(400, result.error);
          return send(response, 201, contestDto(contestById(result.contest.id), true));
        }
      }

      if (resource === 'contests' && id && segments.length <= 3) {
        const contest = contestById(id);
        if (method === 'GET' && !action) return send(response, 200, contestDto(contest, true));
        if (method === 'PUT' && !action) {
          requireUser();
          if (!model.isContestAuthor(id)) throw new ApiError(403, 'Изменять контест могут только его авторы.');
          const values = validateContestInput(body);
          if (values.format !== (contest.format ?? 'standard') || contest.format === 'duel' && values.opponentId !== contest.opponentId) throw new ApiError(400, 'Формат и участников менять нельзя.');
          const result = model.saveContest(id, values);
          if (!result.ok) throw new ApiError(400, result.error);
          return send(response, 200, contestDto(result.contest, true));
        }
        if (method === 'PUT' && action === 'registration') {
          const user = requireUser();
          if (contest.format === 'duel' && ![contest.challengerId, contest.opponentId].includes(user.id)) throw new ApiError(403, 'Дуэль только для приглашенных участников.');
          const result = model.joinContest(id);
          if (!result.ok) throw new ApiError(400, result.error);
          return send(response, 204);
        }
        if (method === 'GET' && action === 'standings') {
          return send(response, 200, model.phase(id) === 'upcoming' ? [] : standings(model.contest(id)));
        }
      }

      throw new ApiError(404, 'Ресурс не найден.');
    } catch (error) {
      if (!response.writableEnded) {
        send(response, error instanceof ApiError ? error.status : error instanceof URIError ? 400 : 500, {
          message: error instanceof ApiError ? error.message : error instanceof URIError ? 'Некорректный адрес.' : 'Ошибка тестового сервера.',
        });
      }
    }
  });

  server.on('close', () => {
    for (const timer of timers) clearTimeout(timer);
    timers.clear();
  });
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = createApiServer();
  const port = Number(process.env.PORT ?? 4187);
  server.listen(port, '127.0.0.1', () => {
    console.log(`Fixture API: http://127.0.0.1:${server.address().port}/api`);
  });
}
