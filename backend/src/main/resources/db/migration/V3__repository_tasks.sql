CREATE TABLE source_problem (
    id text PRIMARY KEY,
    repository_id bigint NOT NULL REFERENCES source_repository(id),
    task_key text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    current_revision uuid REFERENCES problem_revision(id),
    UNIQUE(repository_id, task_key)
);

INSERT INTO source_problem(id, repository_id, task_key, current_revision)
SELECT id::text, id, 'legacy', current_revision FROM source_repository;

ALTER TABLE problem_revision ADD COLUMN problem_id text REFERENCES source_problem(id);
UPDATE problem_revision SET problem_id = repository_id::text;
ALTER TABLE problem_revision ALTER COLUMN problem_id SET NOT NULL;
ALTER TABLE problem_revision DROP CONSTRAINT problem_revision_repository_id_commit_sha_key;
ALTER TABLE problem_revision ADD UNIQUE(problem_id, commit_sha);

CREATE TABLE repository_revision (
    repository_id bigint NOT NULL REFERENCES source_repository(id),
    commit_sha text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(repository_id, commit_sha)
);
INSERT INTO repository_revision(repository_id, commit_sha)
SELECT DISTINCT repository_id, commit_sha FROM problem_revision;

ALTER TABLE source_repository DROP COLUMN current_revision;

ALTER TABLE problem_revision ADD COLUMN metadata jsonb GENERATED ALWAYS AS (
    document - ARRAY['statementMarkdown', 'starterCode', 'generator', 'referenceSolution', 'environmentMarkdown']
) STORED;

CREATE INDEX submission_personal_best ON submission(user_id, problem_id, revision_id, contest_id, time_ms)
WHERE status = 'accepted' AND NOT example;
