-- Minimal first-party credentials and revocable sessions for the commercial MVP.
-- Passwords are scrypt hashes; session tokens are stored only as SHA-256 hashes.

CREATE TABLE commercial_credentials (
    credential_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL UNIQUE REFERENCES persons(person_id),
    access_account_id TEXT NOT NULL UNIQUE REFERENCES access_accounts(access_account_id),
    username TEXT NOT NULL COLLATE NOCASE UNIQUE
        CHECK (length(trim(username)) BETWEEN 3 AND 80),
    password_salt TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','locked','disabled')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE commercial_sessions (
    session_id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES persons(person_id),
    token_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT NULL,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_commercial_sessions_person
    ON commercial_sessions(person_id, expires_at, revoked_at);

CREATE TABLE commercial_login_attempts (
    username_key TEXT PRIMARY KEY,
    window_started_at TEXT NOT NULL,
    attempt_count INTEGER NOT NULL CHECK (typeof(attempt_count) = 'integer' AND attempt_count >= 0),
    updated_at TEXT NOT NULL
);
