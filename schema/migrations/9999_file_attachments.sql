CREATE TABLE IF NOT EXISTS file_attachments (
    attachment_id TEXT PRIMARY KEY,
    storage_ref TEXT NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    media_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0),
    checksum_sha256 TEXT NOT NULL CHECK (length(checksum_sha256) = 64),
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_file_attachments_created
    ON file_attachments(created_at, attachment_id);
