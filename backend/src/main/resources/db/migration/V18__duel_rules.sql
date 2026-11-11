ALTER TABLE gpucodeforces.contests
    ADD COLUMN surrendered_user_id UUID REFERENCES gpucodeforces.users (id),
    ADD COLUMN benchmark_environment_key VARCHAR(64),
    ADD CONSTRAINT contests_surrendered_participant_check CHECK (
        surrendered_user_id IS NULL OR (format = 'duel' AND surrendered_user_id IN (challenger_id, opponent_id))
    );

ALTER TABLE gpucodeforces.duel_requests
    ADD COLUMN problem_version_id UUID REFERENCES gpucodeforces.problem_versions (id);

UPDATE gpucodeforces.duel_requests r
SET problem_version_id = p.problem_version_id
FROM gpucodeforces.contest_problems p
WHERE p.contest_id = r.contest_id AND p.position = 0;

CREATE UNIQUE INDEX duel_one_random_queue_per_user
    ON gpucodeforces.duel_requests (creator_id) WHERE mode = 'random' AND status = 'queued';

CREATE INDEX duel_requests_creator_idx ON gpucodeforces.duel_requests (creator_id, created_at DESC);
CREATE INDEX duel_requests_opponent_idx ON gpucodeforces.duel_requests (opponent_id, created_at DESC);

UPDATE gpucodeforces.duel_active_users SET request_id = NULL;

ALTER TABLE gpucodeforces.notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE gpucodeforces.notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('duel_invitation', 'duel_started', 'duel_declined', 'duel_cancelled', 'duel_surrendered'));
