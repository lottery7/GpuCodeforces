ALTER TABLE gpucodeforces.contests
    ADD COLUMN hack_end_at TIMESTAMPTZ,
    ADD COLUMN finalized_at TIMESTAMPTZ,
    ADD COLUMN baseline_times JSONB NOT NULL DEFAULT '{}';
ALTER TABLE gpucodeforces.contests ADD COLUMN gpu_profile TEXT;
UPDATE gpucodeforces.contests c SET gpu_profile = (
    SELECT s.gpu_profile FROM gpucodeforces.submissions s WHERE s.contest_id = c.id ORDER BY s.created_at LIMIT 1
);

-- Preserve already published results; new contests are finalized explicitly.
UPDATE gpucodeforces.contests SET finalized_at = rating_applied_at WHERE rating_applied_at IS NOT NULL;
CREATE INDEX contests_pending_end_idx ON gpucodeforces.contests(end_at) WHERE finalized_at IS NULL;

UPDATE gpucodeforces.contests c SET baseline_times = (
    SELECT coalesce(jsonb_object_agg(records.problem_id, records.time_ms) FILTER (WHERE records.time_ms IS NOT NULL), '{}')
    FROM (
        SELECT cp.problem_id, coalesce(
            (SELECT min(s.time_ms) FROM gpucodeforces.submissions s
             WHERE s.problem_id = cp.problem_id AND s.contest_id IS NULL AND s.status = 'accepted'
               AND s.judge_hash = v.judge_hash AND s.gpu_profile = coalesce(c.gpu_profile, 'rtx3060') AND s.created_at < c.start_at),
            (SELECT min(s.time_ms) FROM gpucodeforces.submissions s
             WHERE s.problem_id = cp.problem_id AND s.contest_id = c.id AND s.status = 'accepted'
               AND s.judge_hash = v.judge_hash AND s.gpu_profile = coalesce(c.gpu_profile, 'rtx3060'))
        ) AS time_ms
        FROM gpucodeforces.contest_problems cp
        JOIN gpucodeforces.problem_versions v ON v.id = cp.problem_version_id
        WHERE cp.contest_id = c.id
    ) records
) WHERE c.finalized_at IS NOT NULL;

CREATE TABLE gpucodeforces.virtual_participations (
    id UUID PRIMARY KEY,
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests(id),
    user_id UUID NOT NULL REFERENCES gpucodeforces.users(id),
    started_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    CHECK (ends_at > started_at)
);
CREATE INDEX virtual_user_started_idx ON gpucodeforces.virtual_participations(user_id, started_at DESC);
ALTER TABLE gpucodeforces.virtual_participations ADD UNIQUE(id, user_id, contest_id);

ALTER TABLE gpucodeforces.submissions ADD COLUMN context_key TEXT;
UPDATE gpucodeforces.submissions SET context_key = coalesce(contest_id::text, 'practice');
ALTER TABLE gpucodeforces.submissions ALTER COLUMN context_key SET NOT NULL;
ALTER TABLE gpucodeforces.submissions ADD CONSTRAINT submission_context_check CHECK (
    (contest_id IS NULL AND context_key = 'practice') OR
    (contest_id IS NOT NULL AND (context_key = contest_id::text OR context_key = 'upsolve:' || contest_id::text OR context_key LIKE 'virtual:%'))
);
CREATE INDEX submissions_context_idx ON gpucodeforces.submissions(context_key, problem_id, status);
CREATE INDEX submissions_pending_rejudge_idx ON gpucodeforces.submissions(status)
    WHERE contest_id IS NOT NULL AND recheck_count > 0 AND context_key NOT LIKE '%:%';
ALTER TABLE gpucodeforces.submissions ADD COLUMN virtual_participation_id UUID
    GENERATED ALWAYS AS (CASE WHEN context_key LIKE 'virtual:%' THEN substring(context_key from 9)::uuid END) STORED;
ALTER TABLE gpucodeforces.submissions ADD FOREIGN KEY (virtual_participation_id, user_id, contest_id)
    REFERENCES gpucodeforces.virtual_participations(id, user_id, contest_id);

CREATE TABLE gpucodeforces.hacks (
    id UUID PRIMARY KEY,
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests(id),
    user_id UUID NOT NULL REFERENCES gpucodeforces.users(id),
    submission_id UUID NOT NULL REFERENCES gpucodeforces.submissions(id),
    problem_id TEXT NOT NULL,
    test_json TEXT NOT NULL,
    test_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    diagnostic TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    job_id TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE(submission_id, test_hash)
);
CREATE INDEX hacks_contest_idx ON gpucodeforces.hacks(contest_id, status);

CREATE TABLE gpucodeforces.test_suites (
    id TEXT PRIMARY KEY,
    tests_json TEXT NOT NULL
);
INSERT INTO gpucodeforces.test_suites VALUES ('base', '[]');

CREATE TABLE gpucodeforces.judging_runs (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES gpucodeforces.submissions(id),
    run_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    time_ms NUMERIC,
    diagnostic TEXT NOT NULL,
    test_suite TEXT NOT NULL REFERENCES gpucodeforces.test_suites(id),
    finished_at TIMESTAMPTZ,
    UNIQUE(submission_id, run_number)
);
ALTER TABLE gpucodeforces.submissions ADD COLUMN test_suite TEXT NOT NULL DEFAULT 'base' REFERENCES gpucodeforces.test_suites(id);
