ALTER TABLE app_user ADD COLUMN rating integer;
ALTER TABLE app_user ADD COLUMN rating_rank bigint;
UPDATE app_user u SET rating = r.rating
FROM (SELECT user_id, (1500 + sum(delta))::integer AS rating FROM rating_event GROUP BY user_id) r
WHERE r.user_id = u.id;
UPDATE app_user u SET rating_rank = r.position FROM (
    SELECT id, rank() OVER (ORDER BY rating DESC) AS position FROM app_user WHERE rating IS NOT NULL
) r WHERE r.id = u.id;
CREATE INDEX app_user_rating ON app_user(rating DESC) WHERE rating IS NOT NULL;
CREATE INDEX app_user_admin ON app_user(id) WHERE is_admin;
CREATE INDEX rating_event_user ON rating_event(user_id, contest_id);
CREATE INDEX app_session_expiry ON app_session(expires_at);
CREATE INDEX contest_end ON contest(end_at, id);
CREATE INDEX contest_unrated_end ON contest(end_at) WHERE NOT rated;

CREATE INDEX submission_inflight_worker ON submission(worker_id) WHERE finished_at IS NULL;
CREATE INDEX submission_inflight_user ON submission(user_id) WHERE finished_at IS NULL;
CREATE INDEX submission_inflight_deadline ON submission(deadline, worker_id)
WHERE finished_at IS NULL AND worker_id IS NOT NULL;
CREATE INDEX submission_inflight_contest ON submission(contest_id, user_id)
WHERE finished_at IS NULL AND NOT example;
CREATE INDEX submission_contest_user ON submission(contest_id, user_id) WHERE NOT example;

ALTER TABLE submission ADD COLUMN environment_id text;
UPDATE submission s SET environment_id = coalesce(r.document->>'environmentId', 'default')
FROM problem_revision r WHERE r.id = s.revision_id;
ALTER TABLE submission ALTER COLUMN environment_id SET NOT NULL;
CREATE INDEX submission_queue_environment ON submission(environment_id, created_at, id)
WHERE status = 'queued' AND worker_id IS NULL;

CREATE INDEX judge_worker_alive ON judge_worker(id) WHERE alive;
CREATE INDEX judge_worker_ping_deadline ON judge_worker(ping_deadline) WHERE ping_deadline IS NOT NULL;
CREATE INDEX problem_revision_repository ON problem_revision(repository_id);
CREATE INDEX source_repository_poll ON source_repository(coalesce(checked_at, '-infinity'::timestamptz), id)
WHERE enabled;
