CREATE INDEX contests_format_start_idx ON gpucodeforces.contests (format, start_at, id);
CREATE INDEX duel_requests_contest_idx ON gpucodeforces.duel_requests (contest_id);
