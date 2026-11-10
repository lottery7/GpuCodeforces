ALTER TABLE gpucodeforces.submissions
    ADD COLUMN benchmark_environment_key VARCHAR(128),
    ADD COLUMN guess_benchmark_key VARCHAR(160),
    ADD COLUMN guess_result JSONB;

UPDATE gpucodeforces.submissions s
SET guess_benchmark_key = 'legacy-' || s.problem_version_id,
    guess_result = jsonb_build_object(
        'firstTimeMs', v.format_data -> 'firstTimeMs',
        'secondTimeMs', v.format_data -> 'secondTimeMs',
        'explanation', COALESCE(v.format_data ->> 'explanation', ''),
        'benchmarkKey', 'legacy-' || s.problem_version_id,
        'synthetic', COALESCE((v.format_data ->> 'benchmarkSynthetic')::boolean, false)
    )
FROM gpucodeforces.problem_versions v
WHERE s.problem_version_id = v.id AND s.guess_choice IS NOT NULL
    AND jsonb_typeof(v.format_data -> 'firstTimeMs') = 'number'
    AND jsonb_typeof(v.format_data -> 'secondTimeMs') = 'number';

DROP INDEX gpucodeforces.submissions_guess_practice_key;
DROP INDEX gpucodeforces.submissions_guess_contest_key;

CREATE UNIQUE INDEX submissions_guess_practice_key
    ON gpucodeforces.submissions (user_id, problem_version_id, guess_benchmark_key)
    WHERE guess_choice IS NOT NULL AND contest_id IS NULL;

CREATE UNIQUE INDEX submissions_guess_contest_key
    ON gpucodeforces.submissions (user_id, problem_version_id, contest_id, guess_benchmark_key)
    WHERE guess_choice IS NOT NULL AND contest_id IS NOT NULL;
