-- Checkout receipts are the durable idempotency record and the replayable
-- response evidence. Mutating or deleting one would break retry guarantees.
CREATE TRIGGER commercial_checkout_receipt_immutable_update
BEFORE UPDATE ON commercial_checkout_receipts
BEGIN
    SELECT RAISE(ABORT, 'commercial checkout receipt evidence is immutable');
END;

CREATE TRIGGER commercial_checkout_receipt_immutable_delete
BEFORE DELETE ON commercial_checkout_receipts
BEGIN
    SELECT RAISE(ABORT, 'commercial checkout receipt evidence is immutable');
END;
