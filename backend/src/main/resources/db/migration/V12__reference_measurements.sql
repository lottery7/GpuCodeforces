CREATE TABLE gpucodeforces.reference_jobs (
    id UUID PRIMARY KEY,
    problem_version_id UUID NOT NULL REFERENCES gpucodeforces.problem_versions (id),
    environment_key VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('baseline', 'first', 'second')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'running', 'ready', 'failed')),
    generation INTEGER NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0,
    send_attempts INTEGER NOT NULL DEFAULT 0,
    job_id TEXT,
    event_attempt INTEGER NOT NULL DEFAULT 0,
    event_seq INTEGER NOT NULL DEFAULT 0,
    expected_tests INTEGER,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    time_ms NUMERIC,
    measurement JSONB,
    measured_at TIMESTAMPTZ,
    diagnostic TEXT,
    UNIQUE (problem_version_id, environment_key, role),
    CHECK (status <> 'ready' OR (time_ms IS NOT NULL AND time_ms > 0 AND measured_at IS NOT NULL AND measurement IS NOT NULL))
);

CREATE INDEX reference_jobs_pending_idx ON gpucodeforces.reference_jobs (environment_key, status, next_attempt_at);
