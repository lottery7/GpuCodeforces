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

function makeModel() {
  const context = vm.createContext({
    window: {}, localStorage: memoryStorage(), sessionStorage: memoryStorage(),
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
  for (const [wire, model] of Object.entries(modelStatuses)) {
    if (['queued', 'compiling', 'checking', 'measuring'].includes(wire)) continue;
    if (code.includes(`FIXTURE_${wire.toUpperCase()}`)) return model;
  }
  return 'accepted';
}

export function createApiServer() {
  const model = makeModel();
  const sessions = new Map();
  const passwords = new Map(model.users().map(user => [user.id, passwordHash('password123')]));
  const timers = new Set();

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
    const best = targetId ? model.best(problem.id, { userId: targetId, contestId }) : null;
    return {
      id: problem.id, code: problem.code, title: problem.title,
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
      user: publicUser(userById(submission.userId)), contestId: submission.contestId,
      contestTitle: submission.contestId ? contestById(submission.contestId).title : null,
      createdAt: submission.createdAt, status: wireStatuses[submission.status],
      timeMs: submission.status === 'accepted' ? submission.timeMs : null,
      comparable: model.canCompare(submission),
      ...(detail ? {
        passedTests: submission.passedTests, totalTests: submission.totalTests,
        previousBestTimeMs: previous, environmentMarkdown: environmentMarkdown(benchmark),
        ...(owner ? { code: submission.code, diagnostic } : {}),
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
          diagnostic: diagnostics[status] ?? null,
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
    return { ...body, durationMinutes: (end - start) / 60000, gpuId: 'a100' };
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
      const needsBody = method === 'POST' && (resource === 'session' || resource === 'users' || resource === 'submissions' && !id || resource === 'contests' && !id || resource === 'problems' && action === 'example')
        || method === 'PUT' && resource === 'contests' && id && !action;
      const body = needsBody ? await readBody(request) : {};
      model.setSession(sessions.get(sessionToken) ?? null);

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
        const solved = query.get('solved');
        if (difficulty !== null && !['easy', 'medium', 'hard'].includes(difficulty)) throw new ApiError(400, 'Неизвестная сложность.');
        if (solved !== null && !['true', 'false'].includes(solved)) throw new ApiError(400, 'Некорректный фильтр решения.');
        let items = all.map(problem => problemDto(problem, contestId, target)).filter(problem =>
          `${problem.code} ${problem.title}`.toLowerCase().includes(q)
          && (difficulty === null || problem.difficulty === difficulty)
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
            statementMarkdown: statementMarkdown(problem), starterCode: problem.starter,
            timeLimitSeconds: problem.limits.timeSeconds, memoryLimitMb: problem.limits.memoryMb,
            environmentMarkdown: environmentMarkdown(problem.benchmark),
          });
        }
        if (method === 'GET' && action === 'leaders') {
          return send(response, 200, model.leaderboard(id, contestId).map(row => ({
            rank: row.rank, user: publicUser(row.user), timeMs: row.submission.timeMs,
            submissionId: String(row.submission.id),
          })));
        }
        if (method === 'POST' && action === 'example') {
          const code = textBody(body, 'code');
          const outcome = outcomeFor(code);
          return send(response, 200, { passed: outcome === 'accepted', diagnostic: diagnostics[outcome] ?? '' });
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
          problemById(problemId, contestId);
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
          return send(response, 201, contestDto(result.contest, true));
        }
      }

      if (resource === 'contests' && id && segments.length <= 3) {
        const contest = contestById(id);
        if (method === 'GET' && !action) return send(response, 200, contestDto(contest, true));
        if (method === 'PUT' && !action) {
          requireUser();
          if (!model.isContestAuthor(id)) throw new ApiError(403, 'Изменять контест могут только его авторы.');
          const values = validateContestInput(body);
          const result = model.saveContest(id, values);
          if (!result.ok) throw new ApiError(400, result.error);
          return send(response, 200, contestDto(result.contest, true));
        }
        if (method === 'PUT' && action === 'registration') {
          requireUser();
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
