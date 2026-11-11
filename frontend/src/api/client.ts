import createClient from 'openapi-fetch';
import type { paths } from './schema';
import type { ComparisonInput, ContestInput, ContestQuery, DuelInput, DuelQuery, DuelRequest, GuessInput, LoginInput, NotificationQuery, ProblemQuery, RegisterInput, SubmissionQuery, SubmitInput, UserQuery } from './types';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function result<T>(request: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  try {
    const { data, error, response } = await request;
    if (!response.ok) {
      const message = typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string' ? error.message : `Ошибка сервера (${response.status}).`;
      throw new ApiError(response.status, message);
    }
    if (data === undefined && response.status !== 204) throw new ApiError(502, 'Сервер вернул пустой ответ.');
    return data as T;
  } catch (error) {
    if (error instanceof ApiError || typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'Не удалось получить ответ сервера. Проверьте соединение и попробуйте снова.');
  }
}

export function createApi(baseUrl = '/api', fetcher?: typeof fetch) {
  const http = createClient<paths>({ baseUrl, credentials: 'include', ...(fetcher ? { fetch: fetcher } : {}) });
  return {
    getRuntime: (signal?: AbortSignal) => result(http.GET('/runtime', { signal })),
    listDuelProblems: (signal?: AbortSignal) => result(http.GET('/duels/problems', { signal })),
    listDuels: (query: DuelQuery = {}, signal?: AbortSignal) => result(http.GET('/duels', { params: { query }, signal })),
    createDuel: (body: DuelInput) => result(http.POST('/duels', { body })),
    getCurrentDuel: async (signal?: AbortSignal): Promise<DuelRequest | null> => await result(http.GET('/duels/current', { signal })) ?? null,
    getDuel: (id: string, signal?: AbortSignal) => result(http.GET('/duels/{id}', { params: { path: { id } }, signal })),
    acceptDuel: (id: string) => result(http.POST('/duels/{id}/accept', { params: { path: { id } } })),
    declineDuel: (id: string) => result(http.POST('/duels/{id}/decline', { params: { path: { id } } })),
    cancelDuel: (id: string) => result(http.POST('/duels/{id}/cancel', { params: { path: { id } } })),
    listNotifications: (query: NotificationQuery = {}, signal?: AbortSignal) => result(http.GET('/notifications', { params: { query }, signal })),
    readNotification: (id: string) => result(http.PUT('/notifications/{id}/read', { params: { path: { id } } })),
    session: (signal?: AbortSignal) => result(http.GET('/session', { signal })),
    login: (body: LoginInput) => result(http.POST('/session', { body })),
    register: (body: RegisterInput) => result(http.POST('/users', { body })),
    logout: () => result(http.DELETE('/session')),
    listUsers: (query: UserQuery = {}, signal?: AbortSignal) => result(http.GET('/users', { params: { query }, signal })),
    getProfile: (id: string, signal?: AbortSignal) => result(http.GET('/users/{id}', { params: { path: { id } }, signal })),
    listProblems: (query: ProblemQuery = {}, signal?: AbortSignal) => result(http.GET('/problems', { params: { query }, signal })),
    async allProblems(query: ProblemQuery = {}, signal?: AbortSignal) {
      const first = await result(http.GET('/problems', { params: { query: { ...query, offset: 0, limit: 100 } }, signal }));
      const pages = await Promise.all(Array.from({ length: Math.ceil(first.total / 100) - 1 }, (_, index) => result(http.GET('/problems', { params: { query: { ...query, offset: (index + 1) * 100, limit: 100 } }, signal }))));
      return [...first.items, ...pages.flatMap(page => page.items)];
    },
    getProblem: (id: string, context = 'practice', signal?: AbortSignal) => result(http.GET('/problems/{id}', { params: { path: { id }, query: { context } }, signal })),
    problemLeaders: (id: string, context = 'practice', signal?: AbortSignal) => result(http.GET('/problems/{id}/leaders', { params: { path: { id }, query: { context } }, signal })),
    checkExample: (id: string, context: string, code: string) => result(http.POST('/problems/{id}/example', { params: { path: { id }, query: { context } }, body: { code } })),
    submitGuess: (id: string, context: string, body: GuessInput) => result(http.POST('/problems/{id}/guess', { params: { path: { id }, query: { context } }, body })),
    createComparison: (id: string, body: ComparisonInput) => result(http.POST('/problems/{id}/comparisons', { params: { path: { id } }, body })),
    listSubmissions: (query: SubmissionQuery = {}, signal?: AbortSignal) => result(http.GET('/submissions', { params: { query }, signal })),
    submitSolution: (body: SubmitInput) => result(http.POST('/submissions', { body })),
    getSubmission: (id: string, signal?: AbortSignal) => result(http.GET('/submissions/{id}', { params: { path: { id } }, signal })),
    recheckSubmission: (id: string) => result(http.POST('/submissions/{id}/recheck', { params: { path: { id } } })),
    listContests: (query: ContestQuery = {}, signal?: AbortSignal) => result(http.GET('/contests', { params: { query }, signal })),
    getContest: (id: string, signal?: AbortSignal) => result(http.GET('/contests/{id}', { params: { path: { id } }, signal })),
    createContest: (body: ContestInput) => result(http.POST('/contests', { body })),
    updateContest: (id: string, body: ContestInput) => result(http.PUT('/contests/{id}', { params: { path: { id } }, body })),
    registerForContest: (id: string) => result(http.PUT('/contests/{id}/registration', { params: { path: { id } } })),
    contestStandings: (id: string, signal?: AbortSignal) => result(http.GET('/contests/{id}/standings', { params: { path: { id } }, signal })),
    surrenderContest: (id: string) => result(http.POST('/contests/{id}/surrender', { params: { path: { id } } })),
  };
}

export const api = createApi();
