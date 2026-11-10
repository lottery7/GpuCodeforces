CREATE TABLE gpucodeforces.submissions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id),
    problem_id TEXT NOT NULL REFERENCES gpucodeforces.problems (id),
    problem_version_id UUID NOT NULL REFERENCES gpucodeforces.problem_versions (id),
    -- Null: practice.
    contest_id UUID REFERENCES gpucodeforces.contests (id),
    code TEXT NOT NULL,
    -- Status of the frontend contract (queued, compiling, checking, accepted, wrong_answer, ...).
    status TEXT NOT NULL,
    time_ms NUMERIC,
    passed_tests INTEGER NOT NULL,
    total_tests INTEGER,
    diagnostic TEXT NOT NULL,
    environment_markdown TEXT NOT NULL,
    -- Judge configuration: accepted times are comparable only within one (judge_hash, gpu_profile).
    judge_hash TEXT NOT NULL,
    gpu_profile TEXT NOT NULL,
    previous_best_time_ms NUMERIC,
    -- Each recheck is a new judge submission id (<id>.r<n>), the gateway deduplicates by id.
    recheck_count INTEGER NOT NULL,
    -- Job the gateway accepted; null while the submission still has to be sent.
    job_id TEXT,
    -- Last judge event applied, events arrive out of order.
    event_attempt INTEGER NOT NULL,
    event_seq INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX submissions_user_created_idx ON gpucodeforces.submissions (user_id, created_at DESC);
CREATE INDEX submissions_problem_idx ON gpucodeforces.submissions (problem_id, status);
CREATE INDEX submissions_contest_idx ON gpucodeforces.submissions (contest_id) WHERE contest_id IS NOT NULL;
CREATE INDEX submissions_unsent_idx ON gpucodeforces.submissions (created_at) WHERE job_id IS NULL AND status = 'queued';

-- Contest baselines are computed from practice submissions before the start (ContestService.baselines).
ALTER TABLE gpucodeforces.contest_problems DROP COLUMN baseline_time_ms;
