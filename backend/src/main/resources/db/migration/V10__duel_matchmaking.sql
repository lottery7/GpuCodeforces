CREATE TABLE gpucodeforces.duel_matchmaking_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);

INSERT INTO gpucodeforces.duel_matchmaking_lock (id) VALUES (1);

CREATE TABLE gpucodeforces.duel_requests (
    id UUID PRIMARY KEY,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('random', 'invite')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('queued', 'invited', 'matched', 'declined', 'cancelled', 'expired')),
    creator_id UUID NOT NULL REFERENCES gpucodeforces.users (id),
    opponent_id UUID REFERENCES gpucodeforces.users (id),
    topics TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    contest_id UUID REFERENCES gpucodeforces.contests (id),
    CHECK (creator_id <> opponent_id),
    CHECK (mode <> 'invite' OR opponent_id IS NOT NULL),
    CHECK ((status = 'matched') = (contest_id IS NOT NULL))
);

CREATE INDEX duel_requests_waiting_idx ON gpucodeforces.duel_requests (status, created_at);
CREATE INDEX duel_requests_expiration_idx ON gpucodeforces.duel_requests (status, expires_at);

CREATE TABLE gpucodeforces.duel_active_users (
    user_id UUID PRIMARY KEY REFERENCES gpucodeforces.users (id),
    request_id UUID REFERENCES gpucodeforces.duel_requests (id)
);

CREATE INDEX duel_active_users_request_idx ON gpucodeforces.duel_active_users (request_id) WHERE request_id IS NOT NULL;

CREATE TABLE gpucodeforces.notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id),
    type VARCHAR(24) NOT NULL CHECK (type IN ('duel_invitation', 'duel_started', 'duel_declined', 'duel_cancelled')),
    message TEXT NOT NULL,
    duel_request_id UUID NOT NULL REFERENCES gpucodeforces.duel_requests (id),
    contest_id UUID REFERENCES gpucodeforces.contests (id),
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ
);

CREATE INDEX notifications_user_created_idx ON gpucodeforces.notifications (user_id, created_at DESC);
