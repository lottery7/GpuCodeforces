-- The feed of the newest submissions of all users reads a page from the end of this index. Submissions of a virtual
-- attempt are private, so they are kept out of the index and SubmissionRepository.findLatest repeats the condition.
CREATE INDEX submissions_feed_idx ON gpucodeforces.submissions (created_at DESC)
    WHERE context_key NOT LIKE 'virtual:%';
