ALTER TABLE gpucodeforces.contests
    ADD COLUMN final_duel_results JSONB,
    ADD CONSTRAINT contests_final_duel_results_check CHECK (final_duel_results IS NULL OR format = 'duel');
