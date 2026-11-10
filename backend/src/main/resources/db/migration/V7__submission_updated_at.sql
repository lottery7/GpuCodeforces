-- Last sign of life of a submission, for the hang detector (SubmissionService.reportStuck).
ALTER TABLE gpucodeforces.submissions ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE gpucodeforces.submissions SET updated_at = coalesce(finished_at, created_at);
ALTER TABLE gpucodeforces.submissions ALTER COLUMN updated_at SET NOT NULL;
CREATE INDEX submissions_pending_idx ON gpucodeforces.submissions (updated_at)
    WHERE status IN ('queued', 'compiling', 'checking', 'measuring');
