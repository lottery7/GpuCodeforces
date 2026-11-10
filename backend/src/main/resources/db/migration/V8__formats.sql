ALTER TABLE gpucodeforces.problem_versions
    ADD COLUMN format VARCHAR(20) NOT NULL DEFAULT 'standard',
    ADD COLUMN format_data JSONB NOT NULL DEFAULT '{}',
    ADD CONSTRAINT problem_versions_format_check CHECK (format IN ('standard', 'optimization', 'guess'));

ALTER TABLE gpucodeforces.contests
    ADD COLUMN format VARCHAR(20) NOT NULL DEFAULT 'standard',
    ADD COLUMN challenger_id UUID REFERENCES gpucodeforces.users (id),
    ADD COLUMN opponent_id UUID REFERENCES gpucodeforces.users (id),
    ADD CONSTRAINT contests_format_check CHECK (format IN ('standard', 'duel')),
    ADD CONSTRAINT contests_duel_participants_check CHECK (
        (format = 'standard' AND challenger_id IS NULL AND opponent_id IS NULL) OR
        (format = 'duel' AND challenger_id IS NOT NULL AND opponent_id IS NOT NULL AND challenger_id <> opponent_id)
    );

ALTER TABLE gpucodeforces.submissions ADD COLUMN guess_choice VARCHAR(10);

ALTER TABLE gpucodeforces.submissions ADD CONSTRAINT submissions_guess_choice_check
    CHECK (guess_choice IS NULL OR guess_choice IN ('first', 'second', 'equal'));

CREATE UNIQUE INDEX submissions_guess_practice_key
    ON gpucodeforces.submissions (user_id, problem_version_id)
    WHERE guess_choice IS NOT NULL AND contest_id IS NULL;

CREATE UNIQUE INDEX submissions_guess_contest_key
    ON gpucodeforces.submissions (user_id, problem_version_id, contest_id)
    WHERE guess_choice IS NOT NULL AND contest_id IS NOT NULL;
