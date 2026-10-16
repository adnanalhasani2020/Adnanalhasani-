-- A Payment may be linked to an Obligation indirectly through a Settlement.
-- Preserve the per-Payment amount ceiling across that relationship too.
CREATE TRIGGER settlement_payment_amount_within_obligation_insert
BEFORE INSERT ON settlements
WHEN NOT EXISTS (
    SELECT 1
    FROM payments p
    JOIN obligations o ON o.obligation_id = NEW.obligation_id
    WHERE p.payment_id = NEW.payment_id
      AND p.amount_minor <= o.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'settled payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER settlement_payment_amount_within_obligation_update
BEFORE UPDATE OF payment_id, obligation_id ON settlements
WHEN NOT EXISTS (
    SELECT 1
    FROM payments p
    JOIN obligations o ON o.obligation_id = NEW.obligation_id
    WHERE p.payment_id = NEW.payment_id
      AND p.amount_minor <= o.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'settled payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER payment_amount_within_settled_obligation_insert
BEFORE INSERT ON payments
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN obligations o ON o.obligation_id = s.obligation_id
    WHERE s.payment_id = NEW.payment_id
      AND NEW.amount_minor > o.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'settled payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER payment_amount_within_settled_obligation_update
BEFORE UPDATE OF amount_minor ON payments
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN obligations o ON o.obligation_id = s.obligation_id
    WHERE s.payment_id = OLD.payment_id
      AND NEW.amount_minor > o.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'settled payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER obligation_amount_covers_settled_payments_update
BEFORE UPDATE OF amount_minor ON obligations
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    WHERE s.obligation_id = OLD.obligation_id
      AND p.amount_minor > NEW.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'obligation amount_minor must cover settled payment amount_minor');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0034_settlement_payment_obligation_amount_consistency',
        strftime('%Y-%m-%dT%H:%M:%fZ','now'));
