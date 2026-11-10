CREATE TABLE app_user (
    id text PRIMARY KEY,
    handle text NOT NULL,
    email text NOT NULL,
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_handle ON app_user (lower(handle));
CREATE UNIQUE INDEX app_user_email ON app_user (lower(email));
CREATE TABLE app_session (
    token_hash text PRIMARY KEY,
    user_id text NOT NULL REFERENCES app_user(id),
    expires_at timestamptz NOT NULL
);
CREATE TABLE source_repository (
    id bigint PRIMARY KEY,
    installation_id bigint NOT NULL,
    full_name text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    checked_at timestamptz,
    error text,
    current_revision uuid
);
CREATE TABLE problem_revision (
    id uuid PRIMARY KEY,
    repository_id bigint NOT NULL REFERENCES source_repository(id),
    commit_sha text NOT NULL,
    document jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(repository_id, commit_sha)
);
ALTER TABLE source_repository ADD FOREIGN KEY (current_revision) REFERENCES problem_revision(id);
CREATE TABLE contest (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    rated boolean NOT NULL DEFAULT false,
    CHECK(end_at > start_at)
);
CREATE TABLE contest_author (
    contest_id uuid NOT NULL REFERENCES contest(id),
    user_id text NOT NULL REFERENCES app_user(id),
    PRIMARY KEY(contest_id, user_id)
);
CREATE TABLE contest_problem (
    contest_id uuid NOT NULL REFERENCES contest(id),
    problem_id text NOT NULL,
    revision_id uuid NOT NULL REFERENCES problem_revision(id),
    position integer NOT NULL,
    PRIMARY KEY(contest_id, problem_id),
    UNIQUE(contest_id, position)
);
CREATE TABLE contest_registration (
    contest_id uuid NOT NULL REFERENCES contest(id),
    user_id text NOT NULL REFERENCES app_user(id),
    PRIMARY KEY(contest_id, user_id)
);
CREATE TABLE judge_worker (
    id text PRIMARY KEY,
    alive boolean NOT NULL DEFAULT false,
    ping_nonce uuid,
    ping_deadline timestamptz,
    next_ping_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE submission (
    id uuid PRIMARY KEY,
    user_id text NOT NULL REFERENCES app_user(id),
    problem_id text NOT NULL,
    revision_id uuid NOT NULL REFERENCES problem_revision(id),
    contest_id uuid REFERENCES contest(id),
    code text NOT NULL,
    example boolean NOT NULL DEFAULT false,
    test_count integer NOT NULL CHECK(test_count > 0),
    status text NOT NULL DEFAULT 'queued',
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    worker_id text REFERENCES judge_worker(id),
    attempt_id uuid,
    attempts integer NOT NULL DEFAULT 0,
    deadline timestamptz,
    time_ms double precision,
    diagnostic text NOT NULL DEFAULT ''
);
CREATE INDEX submission_queue ON submission(created_at) WHERE status = 'queued';
CREATE INDEX submission_owner ON submission(user_id, created_at DESC);
CREATE INDEX submission_result ON submission(problem_id, revision_id, contest_id) WHERE status = 'accepted' AND NOT example;
CREATE TABLE test_result (
    submission_id uuid NOT NULL REFERENCES submission(id),
    test_number integer NOT NULL CHECK(test_number > 0),
    verdict text NOT NULL,
    time_ms double precision,
    diagnostic text NOT NULL,
    PRIMARY KEY(submission_id, test_number)
);
CREATE TABLE judge_inbox (
    id bigserial PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES submission(id),
    attempt_id uuid NOT NULL,
    test_number integer NOT NULL,
    document jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(submission_id, attempt_id, test_number)
);
CREATE TABLE rating_event (
    contest_id uuid NOT NULL REFERENCES contest(id),
    user_id text NOT NULL REFERENCES app_user(id),
    delta integer NOT NULL,
    rating_after integer NOT NULL,
    PRIMARY KEY(contest_id, user_id)
);
CREATE TABLE platform_lease (
    name text PRIMARY KEY,
    owner uuid,
    expires_at timestamptz NOT NULL DEFAULT now(),
    next_discovery_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO platform_lease(name) VALUES ('github');
CREATE TABLE github_budget (
    singleton boolean PRIMARY KEY CHECK(singleton),
    window_start timestamptz NOT NULL DEFAULT now(),
    used integer NOT NULL DEFAULT 0,
    blocked_until timestamptz NOT NULL DEFAULT now()
);
INSERT INTO github_budget(singleton) VALUES (true);
