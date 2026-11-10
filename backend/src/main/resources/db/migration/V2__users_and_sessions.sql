CREATE TABLE gpucodeforces.users (
    id UUID PRIMARY KEY,
    handle VARCHAR(24) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    rating INTEGER,
    rank INTEGER,
    rating_delta INTEGER,
    created_at TIMESTAMPTZ NOT NULL
);

-- Case-insensitive uniqueness: handle/email collisions must be rejected regardless of letter case.
CREATE UNIQUE INDEX users_handle_lower_key ON gpucodeforces.users (lower(handle));
CREATE UNIQUE INDEX users_email_lower_key ON gpucodeforces.users (lower(email));

CREATE TABLE gpucodeforces.sessions (
    token UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_user_id_idx ON gpucodeforces.sessions (user_id);
