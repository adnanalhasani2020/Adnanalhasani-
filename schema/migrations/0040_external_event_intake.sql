-- Durable intake identity for external Hospital events.
-- This stores transport/correlation facts only; it does not create ledger facts.
CREATE TABLE external_event_intake (
    external_event_intake_id TEXT PRIMARY KEY,
    source_system TEXT NOT NULL CHECK (source_system = 'hospital'),
    source_event_key TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (
        event_type IN ('hospital.invoice.created', 'hospital.payment.received')
    ),
    source_aggregate_type TEXT NOT NULL CHECK (source_aggregate_type IN ('invoice', 'payment')),
    source_aggregate_id TEXT NOT NULL CHECK (length(trim(source_aggregate_id)) > 0),
    payload_sha256 TEXT NOT NULL CHECK (
        length(payload_sha256) = 64
        AND payload_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
    currency_code TEXT NOT NULL CHECK (
        length(currency_code) = 3
        AND currency_code GLOB '[A-Z][A-Z][A-Z]'
    ),
    occurred_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'received' CHECK (
        status IN ('received', 'mapped', 'rejected', 'quarantined')
    ),
    finance_entity_type TEXT NULL,
    finance_entity_id TEXT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    mapped_at TEXT NULL,
    last_error TEXT NULL,
    UNIQUE (source_system, source_event_key),
    CHECK (
        (status = 'mapped' AND finance_entity_type IS NOT NULL
            AND length(trim(finance_entity_type)) > 0
            AND finance_entity_id IS NOT NULL
            AND length(trim(finance_entity_id)) > 0
            AND mapped_at IS NOT NULL)
        OR
        (status <> 'mapped' AND finance_entity_type IS NULL
            AND finance_entity_id IS NULL AND mapped_at IS NULL)
    ),
    CHECK (event_type <> 'hospital.invoice.created'
        OR source_aggregate_type = 'invoice'),
    CHECK (event_type <> 'hospital.payment.received'
        OR source_aggregate_type = 'payment')
);

CREATE INDEX external_event_intake_status_seen
    ON external_event_intake(status, last_seen_at);

-- Record identity collisions without retaining the incoming payload itself.
-- A reused source key with a different digest must be quarantined by the
-- consumer, never silently treated as a retry or overwritten.
CREATE TABLE external_event_intake_conflicts (
    external_event_intake_conflict_id TEXT PRIMARY KEY,
    external_event_intake_id TEXT NOT NULL
        REFERENCES external_event_intake(external_event_intake_id),
    observed_payload_sha256 TEXT NOT NULL CHECK (
        length(observed_payload_sha256) = 64
        AND observed_payload_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    observed_at TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0)
);

CREATE INDEX external_event_intake_conflicts_source
    ON external_event_intake_conflicts(external_event_intake_id, observed_at);
