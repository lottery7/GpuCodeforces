-- Spring Data's IgnoreCase predicates use upper(), whereas the uniqueness indexes use lower().
CREATE INDEX users_handle_upper_idx ON gpucodeforces.users (upper(handle));
CREATE INDEX users_email_upper_idx ON gpucodeforces.users (upper(email));

-- Repinning starts with a problem, not a contest: the composite primary key has the reverse order.
CREATE INDEX contest_problems_problem_idx ON gpucodeforces.contest_problems (problem_id);
