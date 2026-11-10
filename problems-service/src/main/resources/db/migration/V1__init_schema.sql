-- Problems catalog table
CREATE TABLE problems (
    id              UUID PRIMARY KEY,
    slug            VARCHAR(150) NOT NULL UNIQUE,
    title           VARCHAR(255) NOT NULL,
    tags            JSONB NOT NULL DEFAULT '[]'::jsonb,
    author          VARCHAR(255),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    statement       TEXT NOT NULL,
    git_commit_sha  VARCHAR(64),
    content_hash    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- GIN index for tag containment / overlap queries
CREATE INDEX idx_problems_tags ON problems USING GIN (tags);

-- Indexes for common queries
CREATE INDEX idx_problems_created_at ON problems (created_at);
CREATE INDEX idx_problems_updated_at ON problems (updated_at);
CREATE INDEX idx_problems_title ON problems (title);

-- Sync state table (single row retained for latest sync status)
CREATE TABLE problem_sync_state (
    id                    INTEGER PRIMARY KEY DEFAULT 1,
    status                VARCHAR(32) NOT NULL,
    last_attempted_at     TIMESTAMPTZ,
    last_success_at       TIMESTAMPTZ,
    last_imported_sha     VARCHAR(64),
    total_discovered      INTEGER NOT NULL DEFAULT 0,
    imported              INTEGER NOT NULL DEFAULT 0,
    updated               INTEGER NOT NULL DEFAULT 0,
    unchanged             INTEGER NOT NULL DEFAULT 0,
    error_message         TEXT,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT problem_sync_state_single_row CHECK (id = 1)
);