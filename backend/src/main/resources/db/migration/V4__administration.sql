ALTER TABLE app_user ADD COLUMN is_admin boolean NOT NULL DEFAULT false;

CREATE TABLE admin_bootstrap (
    singleton boolean PRIMARY KEY CHECK(singleton),
    claimed_by text NOT NULL REFERENCES app_user(id),
    claimed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE runtime_settings (
    singleton boolean PRIMARY KEY CHECK(singleton),
    version bigint NOT NULL DEFAULT 0,
    document jsonb NOT NULL DEFAULT '{}'
);
INSERT INTO runtime_settings(singleton) VALUES (true);

CREATE TABLE admin_audit (
    id uuid PRIMARY KEY,
    actor_id text NOT NULL REFERENCES app_user(id),
    action text NOT NULL,
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
