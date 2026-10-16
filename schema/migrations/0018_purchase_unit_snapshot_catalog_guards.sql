-- Database-level validation for purchase-line unit snapshots.
-- Migration 0011 remains independently applicable to the purchases-only branch;
-- this guard is installed in the combined branch where the product-unit catalog exists.
CREATE TRIGGER trg_purchase_line_unit_snapshot_catalog_guard
BEFORE INSERT ON purchase_lines
WHEN NEW.product_unit_setting_id IS NOT NULL
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM product_unit_settings setting
            JOIN measurement_units unit ON unit.unit_id = setting.unit_id
            WHERE setting.product_unit_setting_id = NEW.product_unit_setting_id
              AND setting.product_id = NEW.product_id
              AND setting.unit_id = NEW.measurement_unit_id
              AND setting.state = 'active'
              AND unit.state = 'active'
              AND setting.conversion_numerator = NEW.conversion_numerator
              AND setting.conversion_denominator = NEW.conversion_denominator
              AND setting.version_no = NEW.product_unit_setting_version
              AND unit.version_no = NEW.measurement_unit_version
              AND unit.code = NEW.unit_code_snapshot
              AND unit.name = NEW.unit_name_snapshot
              AND unit.dimension = NEW.unit_dimension_snapshot
        )
        THEN RAISE(ABORT, 'purchase unit snapshot does not match active product unit configuration')
    END;
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0018_purchase_unit_snapshot_catalog_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
