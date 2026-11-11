ALTER TABLE gpucodeforces.submissions ADD COLUMN expected_environment JSONB;
ALTER TABLE gpucodeforces.reference_jobs ADD COLUMN expected_environment JSONB;
