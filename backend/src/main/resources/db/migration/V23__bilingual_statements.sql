-- A package now carries the statement in two languages (docs/backend/problem-package.md): title and
-- statement_markdown are the Russian text, these columns the English one. Versions imported before that have
-- one text, whatever its language, and it is shown for both.
ALTER TABLE gpucodeforces.problem_versions
    ADD COLUMN title_en TEXT,
    ADD COLUMN statement_markdown_en TEXT;
