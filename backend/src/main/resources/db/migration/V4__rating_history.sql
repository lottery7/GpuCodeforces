ALTER TABLE gpucodeforces.users ADD COLUMN rating_contest_id UUID REFERENCES gpucodeforces.contests (id);

CREATE TABLE gpucodeforces.rating_history (
    contest_id UUID NOT NULL REFERENCES gpucodeforces.contests (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES gpucodeforces.users (id) ON DELETE CASCADE,
    rank INTEGER NOT NULL,
    rating_delta INTEGER NOT NULL,
    rating_after INTEGER NOT NULL,
    PRIMARY KEY (contest_id, user_id)
);

CREATE INDEX rating_history_user_id_idx ON gpucodeforces.rating_history (user_id);
