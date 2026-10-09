-- SPEC-0005 §10.1, §§28.6–28.7, invariants 16–17, AC-04:
-- a Sale must retain the Activity context of its referenced Offering.
--
-- Triggers are used instead of rebuilding sales to add a composite FK.
-- See docs/05-نموذج-البيانات/قرار-تصميم-اتساق-سياق-Sale-Offering.md.
-- This migration is additive and intentionally does not rewrite existing rows.
PRAGMA foreign_keys = ON;

CREATE TRIGGER sales_activity_matches_offering_on_insert
BEFORE INSERT ON sales
FOR EACH ROW
WHEN EXISTS (
    SELECT 1
    FROM offerings AS o
    WHERE o.offering_id = NEW.offering_id
      AND o.activity_id <> NEW.activity_id
)
BEGIN
    SELECT RAISE(ABORT, 'sale activity must match offering activity');
END;

CREATE TRIGGER sales_activity_matches_offering_on_update
BEFORE UPDATE OF offering_id, activity_id ON sales
FOR EACH ROW
WHEN EXISTS (
    SELECT 1
    FROM offerings AS o
    WHERE o.offering_id = NEW.offering_id
      AND o.activity_id <> NEW.activity_id
)
BEGIN
    SELECT RAISE(ABORT, 'sale activity must match offering activity');
END;

CREATE TRIGGER offering_activity_preserves_sales_context
BEFORE UPDATE OF activity_id ON offerings
FOR EACH ROW
WHEN EXISTS (
    SELECT 1
    FROM sales AS s
    WHERE s.offering_id = OLD.offering_id
      AND s.activity_id <> NEW.activity_id
)
BEGIN
    SELECT RAISE(ABORT, 'offering activity update would break sale activity context');
END;
