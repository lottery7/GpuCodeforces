import type { components, operations } from './schema';

export type User = components['schemas']['User'];
export type Problem = components['schemas']['Problem'];
export type Submission = components['schemas']['Submission'];
export type Contest = components['schemas']['Contest'];
export type ContestInput = components['schemas']['ContestInput'];
export type ProblemDetail = operations['getProblem']['responses'][200]['content']['application/json'];
export type Profile = operations['getUserProfile']['responses'][200]['content']['application/json'];
export type Standing = operations['getContestStandings']['responses'][200]['content']['application/json'][number];
export type ProblemQuery = NonNullable<operations['listProblems']['parameters']['query']>;
export type SubmissionQuery = NonNullable<operations['listSubmissions']['parameters']['query']>;
export type ContestQuery = NonNullable<operations['listContests']['parameters']['query']>;
export type UserQuery = NonNullable<operations['listUsers']['parameters']['query']>;
export type LoginInput = operations['login']['requestBody']['content']['application/json'];
export type RegisterInput = operations['registerUser']['requestBody']['content']['application/json'];
export type SubmitInput = operations['submitSolution']['requestBody']['content']['application/json'];
