CREATE INDEX contests_start_idx ON gpucodeforces.contests (start_at, id);
CREATE INDEX contests_end_idx ON gpucodeforces.contests (end_at);
-- The list of contests of one processor, in its display order.
CREATE INDEX contests_processor_start_idx ON gpucodeforces.contests (processor, start_at, id);
CREATE INDEX problems_active_idx ON gpucodeforces.problems (id, current_version_id) WHERE current_version_id IS NOT NULL;

-- Unfiltered user pages follow their display order without sorting the complete population. The rated pages of a
-- processor follow user_ratings_processor_rank_idx.
CREATE INDEX users_handle_order_idx ON gpucodeforces.users (handle, id);
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX users_handle_trgm_idx ON gpucodeforces.users USING gin (lower(handle) gin_trgm_ops);
