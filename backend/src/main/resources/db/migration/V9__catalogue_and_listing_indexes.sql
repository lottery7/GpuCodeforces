CREATE INDEX contests_start_idx ON gpucodeforces.contests (start_at, id);
CREATE INDEX contests_end_idx ON gpucodeforces.contests (end_at);
CREATE INDEX problems_active_idx ON gpucodeforces.problems (id, current_version_id) WHERE current_version_id IS NOT NULL;

-- Unfiltered user pages follow their display order without sorting the complete population.
CREATE INDEX users_handle_order_idx ON gpucodeforces.users (handle, id);
CREATE INDEX users_rank_order_idx ON gpucodeforces.users (rank, handle, id) WHERE rating IS NOT NULL;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX users_handle_trgm_idx ON gpucodeforces.users USING gin (lower(handle) gin_trgm_ops);

-- Best-time sorting needs one minimum per problem, rather than every accepted submission's code.
CREATE INDEX submissions_best_time_idx ON gpucodeforces.submissions
    (problem_id, user_id, judge_hash, gpu_profile, contest_id, time_ms)
    WHERE status = 'accepted';
