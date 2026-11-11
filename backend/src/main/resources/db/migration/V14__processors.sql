-- Processors (judge-api Processors): a submission is judged on a processor in a language and gets a typed score
-- instead of a time; a contest runs on one processor; ratings are kept per processor. Everything that exists
-- was judged on the real GPU in CUDA, i.e. on gpu-cuda.

-- Submissions: processor, language and score; results are comparable within one (judge_hash, processor).
ALTER TABLE gpucodeforces.submissions
    ADD COLUMN processor TEXT,
    ADD COLUMN language TEXT,
    ADD COLUMN score NUMERIC,
    ADD COLUMN score_type TEXT,
    -- Further numbers of the judge's result (cycles, cells, ...), by name.
    ADD COLUMN metrics JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN previous_best_score NUMERIC;
UPDATE gpucodeforces.submissions
SET processor = 'gpu-cuda',
    language = 'cuda',
    score = time_ms,
    score_type = CASE WHEN time_ms IS NOT NULL THEN 'TIME_MS' END,
    previous_best_score = previous_best_time_ms;
ALTER TABLE gpucodeforces.submissions
    ALTER COLUMN processor SET NOT NULL,
    ALTER COLUMN language SET NOT NULL,
    DROP COLUMN time_ms,
    DROP COLUMN previous_best_time_ms,
    DROP COLUMN gpu_profile;
-- Leaders and records of a problem are read per processor.
DROP INDEX gpucodeforces.submissions_problem_idx;
CREATE INDEX submissions_problem_idx ON gpucodeforces.submissions (problem_id, processor, status);

-- Contests: the processor the organizer chose instead of the display name of the platform GPU.
ALTER TABLE gpucodeforces.contests ADD COLUMN processor TEXT NOT NULL DEFAULT 'gpu-cuda';
ALTER TABLE gpucodeforces.contests ALTER COLUMN processor DROP DEFAULT;
ALTER TABLE gpucodeforces.contests DROP COLUMN gpu;

-- Problem versions: the processors a problem declares (for filters) and a starter per processor and language.
ALTER TABLE gpucodeforces.problem_versions
    ADD COLUMN processors TEXT[],
    ADD COLUMN starters JSONB;
UPDATE gpucodeforces.problem_versions
SET processors = '{gpu-cuda}',
    starters = jsonb_build_object('gpu-cuda', jsonb_build_object('cuda', starter_code));
ALTER TABLE gpucodeforces.problem_versions
    ALTER COLUMN processors SET NOT NULL,
    ALTER COLUMN starters SET NOT NULL,
    DROP COLUMN starter_code;

-- Ratings: one per processor. The single rating so far was earned on gpu-cuda.
CREATE TABLE gpucodeforces.user_ratings (
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id) ON DELETE CASCADE,
    processor TEXT NOT NULL,
    rating INTEGER NOT NULL,
    -- Place among the rated users of this processor, precomputed by the rating finalization.
    rank INTEGER,
    rating_delta INTEGER,
    rating_contest_id UUID REFERENCES gpucodeforces.contests (id),
    PRIMARY KEY (user_id, processor)
);
CREATE INDEX user_ratings_processor_rank_idx ON gpucodeforces.user_ratings (processor, rank);
INSERT INTO gpucodeforces.user_ratings (user_id, processor, rating, rank, rating_delta, rating_contest_id)
SELECT id, 'gpu-cuda', rating, rank, rating_delta, rating_contest_id FROM gpucodeforces.users WHERE rating IS NOT NULL;
ALTER TABLE gpucodeforces.users
    DROP COLUMN rating,
    DROP COLUMN rank,
    DROP COLUMN rating_delta,
    DROP COLUMN rating_contest_id;

ALTER TABLE gpucodeforces.rating_history ADD COLUMN processor TEXT;
UPDATE gpucodeforces.rating_history h SET processor = c.processor FROM gpucodeforces.contests c WHERE c.id = h.contest_id;
ALTER TABLE gpucodeforces.rating_history ALTER COLUMN processor SET NOT NULL;

-- The tests a processor runs for a judge hash, as the judge reported them: the "Environment" block lists them.
-- The backend cannot run a problem's generator itself, so the list is known after the first complete judging.
CREATE TABLE gpucodeforces.processor_tests (
    judge_hash TEXT NOT NULL,
    processor TEXT NOT NULL,
    -- [{name, sample, measured}] in order.
    tests JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (judge_hash, processor)
);

-- Formats on every processor (docs/backend/formats.md). A reference job measures one solution on one processor in one
-- language, and its result is a score of that processor; the jobs so far measured CUDA on the real GPU.
ALTER TABLE gpucodeforces.reference_jobs
    ADD COLUMN processor TEXT NOT NULL DEFAULT 'gpu-cuda',
    ADD COLUMN language TEXT NOT NULL DEFAULT 'cuda',
    ADD COLUMN score_type TEXT;
ALTER TABLE gpucodeforces.reference_jobs RENAME COLUMN time_ms TO score;
UPDATE gpucodeforces.reference_jobs SET score_type = 'TIME_MS' WHERE score IS NOT NULL;
ALTER TABLE gpucodeforces.reference_jobs
    ALTER COLUMN processor DROP DEFAULT,
    ALTER COLUMN language DROP DEFAULT;

-- An answer to a guess keeps the scores of both variants with their type; the answers so far compared GPU times.
UPDATE gpucodeforces.submissions
SET guess_result = guess_result || jsonb_build_object(
    'firstScore', guess_result -> 'firstTimeMs', 'secondScore', guess_result -> 'secondTimeMs', 'scoreType', 'TIME_MS')
WHERE guess_result IS NOT NULL AND guess_result ? 'firstTimeMs';

-- A duel runs on one processor, like any contest.
ALTER TABLE gpucodeforces.duel_requests ADD COLUMN processor TEXT NOT NULL DEFAULT 'gpu-cuda';
ALTER TABLE gpucodeforces.duel_requests ALTER COLUMN processor DROP DEFAULT;
