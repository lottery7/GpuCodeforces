-- Problem bank imported from the problem repository (see docs/backend/problem-package.md).
-- Every distinct content of a package is a version; the bank shows the current one, contests and submissions keep theirs.
CREATE TABLE gpucodeforces.problems (
    id TEXT PRIMARY KEY,
    -- Null once the package disappears from the repository: the problem leaves the bank, its history stays.
    current_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gpucodeforces.problem_versions (
    id UUID PRIMARY KEY,
    problem_id TEXT NOT NULL REFERENCES gpucodeforces.problems (id),
    code TEXT NOT NULL,
    title TEXT NOT NULL,
    difficulty TEXT NOT NULL CHECK (difficulty IN ('easy', 'medium', 'hard')),
    tags TEXT[] NOT NULL,
    -- Handles of the problem authors; drafts are visible only to them.
    authors TEXT[] NOT NULL,
    draft BOOLEAN NOT NULL,
    statement_markdown TEXT NOT NULL,
    starter_code TEXT NOT NULL,
    -- Complete judge ProblemSpec, generator included; never returned to clients.
    judge_spec JSONB NOT NULL,
    content_hash TEXT NOT NULL,
    -- Hash of what the judge sees (problem.json + generator): results are comparable only within one judge hash.
    judge_hash TEXT NOT NULL,
    revision TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (problem_id, content_hash)
);

ALTER TABLE gpucodeforces.problems
    ADD CONSTRAINT problems_current_version_fk FOREIGN KEY (current_version_id)
        REFERENCES gpucodeforces.problem_versions (id) DEFERRABLE INITIALLY DEFERRED;

-- Version a contest uses; follows the bank until the contest starts (see ProblemSyncService).
ALTER TABLE gpucodeforces.contest_problems
    ADD COLUMN problem_version_id UUID REFERENCES gpucodeforces.problem_versions (id);
