-- Retirement is a one-way lifecycle transition for the shared measurement-unit catalog
-- and product-specific unit configuration. Preserve history; create a new record if needed.
CREATE TRIGGER trg_measurement_units_no_reactivation
BEFORE UPDATE OF state ON measurement_units
WHEN OLD.state = 'retired' AND NEW.state = 'active'
BEGIN
    SELECT RAISE(ABORT, 'retired measurement units cannot be reactivated');
END;

CREATE TRIGGER trg_product_unit_settings_no_reactivation
BEFORE UPDATE OF state ON product_unit_settings
WHEN OLD.state = 'retired' AND NEW.state = 'active'
BEGIN
    SELECT RAISE(ABORT, 'retired product unit settings cannot be reactivated');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0026_unit_retirement_is_irreversible', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
