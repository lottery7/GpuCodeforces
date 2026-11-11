ALTER TABLE gpucodeforces.problems
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'package',
    ADD CONSTRAINT problems_source_check CHECK (source IN ('package', 'comparison'));
