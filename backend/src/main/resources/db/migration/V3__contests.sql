CREATE TABLE gpucodeforces.contests (
    id UUID PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    gpu VARCHAR(100) NOT NULL,
    -- Set once the rating finalization job (step 4) has applied this contest's results.
    rating_applied_at TIMESTAMPTZ
);

CREATE TABLE gpucodeforces.contest_authors (
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id) ON DELETE CASCADE,
    PRIMARY KEY (contest_id, user_id)
);

CREATE INDEX contest_authors_user_id_idx ON gpucodeforces.contest_authors (user_id);

-- problem_id is a foreign id owned by the problems module (not necessarily a UUID), so it is a plain string here.
CREATE TABLE gpucodeforces.contest_problems (
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests (id) ON DELETE CASCADE,
    problem_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    -- Snapshotted from the problem's global record time when the contest starts; null until then.
    baseline_time_ms NUMERIC,
    PRIMARY KEY (contest_id, problem_id)
);

CREATE UNIQUE INDEX contest_problems_position_key ON gpucodeforces.contest_problems (contest_id, position);

CREATE TABLE gpucodeforces.contest_registrations (
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id) ON DELETE CASCADE,
    PRIMARY KEY (contest_id, user_id)
);

CREATE INDEX contest_registrations_user_id_idx ON gpucodeforces.contest_registrations (user_id);
