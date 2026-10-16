-- A Payment that already participates in a Settlement must retain that
-- Settlement's obligation identity. Currency equality alone is not sufficient.
CREATE TRIGGER payment_obligation_matches_existing_settlements_update
BEFORE UPDATE OF obligation_id ON payments
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    WHERE s.payment_id = OLD.payment_id
      AND (
          NEW.obligation_id IS NULL
          OR NEW.obligation_id <> s.obligation_id
      )
)
BEGIN
    SELECT RAISE(ABORT, 'payment obligation must match existing settlements');
END;
