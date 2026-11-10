-- Problem scripts: canonical solution and test generator, sourced as
-- solution.py / tests.py living next to problem.md inside the Git repo.
ALTER TABLE problems
    ADD COLUMN solution_py TEXT,
    ADD COLUMN tests_py TEXT;