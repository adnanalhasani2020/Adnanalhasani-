-- Link the local commercial profile/session boundary to a Supabase Auth user.
-- Password verification remains exclusively with Supabase Auth.
CREATE TABLE commercial_supabase_identities (
    supabase_user_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL UNIQUE REFERENCES persons(person_id),
    email TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX ix_commercial_supabase_identities_person
    ON commercial_supabase_identities(person_id);
