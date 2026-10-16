-- Immutable unit-conversion snapshot for historical purchase lines.
-- Nullable fields preserve existing and legacy purchases. Unit catalog foreign-key
-- validation is performed by the application when the product-unit catalog exists.
ALTER TABLE purchase_lines ADD COLUMN product_unit_setting_id TEXT NULL;
ALTER TABLE purchase_lines ADD COLUMN measurement_unit_id TEXT NULL;
ALTER TABLE purchase_lines ADD COLUMN unit_code_snapshot TEXT NULL;
ALTER TABLE purchase_lines ADD COLUMN unit_name_snapshot TEXT NULL;
ALTER TABLE purchase_lines ADD COLUMN unit_dimension_snapshot TEXT NULL;
ALTER TABLE purchase_lines ADD COLUMN conversion_numerator INTEGER NULL
    CHECK (conversion_numerator IS NULL OR (typeof(conversion_numerator) = 'integer' AND conversion_numerator > 0));
ALTER TABLE purchase_lines ADD COLUMN conversion_denominator INTEGER NULL
    CHECK (conversion_denominator IS NULL OR (typeof(conversion_denominator) = 'integer' AND conversion_denominator > 0));
ALTER TABLE purchase_lines ADD COLUMN product_unit_setting_version INTEGER NULL
    CHECK (product_unit_setting_version IS NULL OR (typeof(product_unit_setting_version) = 'integer' AND product_unit_setting_version > 0));
ALTER TABLE purchase_lines ADD COLUMN measurement_unit_version INTEGER NULL
    CHECK (measurement_unit_version IS NULL OR (typeof(measurement_unit_version) = 'integer' AND measurement_unit_version > 0));

CREATE TRIGGER trg_purchase_line_unit_snapshot_all_or_none_insert
BEFORE INSERT ON purchase_lines
WHEN NOT (
    NEW.product_unit_setting_id IS NULL
    AND NEW.measurement_unit_id IS NULL
    AND NEW.unit_code_snapshot IS NULL
    AND NEW.unit_name_snapshot IS NULL
    AND NEW.unit_dimension_snapshot IS NULL
    AND NEW.conversion_numerator IS NULL
    AND NEW.conversion_denominator IS NULL
    AND NEW.product_unit_setting_version IS NULL
    AND NEW.measurement_unit_version IS NULL
) AND NOT (
    NEW.product_unit_setting_id IS NOT NULL
    AND NEW.measurement_unit_id IS NOT NULL
    AND NEW.unit_code_snapshot IS NOT NULL
    AND length(trim(NEW.unit_code_snapshot)) > 0
    AND NEW.unit_name_snapshot IS NOT NULL
    AND length(trim(NEW.unit_name_snapshot)) > 0
    AND NEW.unit_dimension_snapshot IS NOT NULL
    AND length(trim(NEW.unit_dimension_snapshot)) > 0
    AND NEW.conversion_numerator IS NOT NULL
    AND NEW.conversion_denominator IS NOT NULL
    AND NEW.product_unit_setting_version IS NOT NULL
    AND NEW.measurement_unit_version IS NOT NULL
)
BEGIN
    SELECT RAISE(ABORT, 'purchase unit snapshot must be complete or entirely absent');
END;

CREATE TRIGGER trg_purchase_line_unit_snapshot_immutable
BEFORE UPDATE OF product_unit_setting_id, measurement_unit_id, unit_code_snapshot,
                 unit_name_snapshot, unit_dimension_snapshot, conversion_numerator,
                 conversion_denominator, product_unit_setting_version, measurement_unit_version
ON purchase_lines
WHEN OLD.product_unit_setting_id IS NOT NEW.product_unit_setting_id
  OR OLD.measurement_unit_id IS NOT NEW.measurement_unit_id
  OR OLD.unit_code_snapshot IS NOT NEW.unit_code_snapshot
  OR OLD.unit_name_snapshot IS NOT NEW.unit_name_snapshot
  OR OLD.unit_dimension_snapshot IS NOT NEW.unit_dimension_snapshot
  OR OLD.conversion_numerator IS NOT NEW.conversion_numerator
  OR OLD.conversion_denominator IS NOT NEW.conversion_denominator
  OR OLD.product_unit_setting_version IS NOT NEW.product_unit_setting_version
  OR OLD.measurement_unit_version IS NOT NEW.measurement_unit_version
BEGIN
    SELECT RAISE(ABORT, 'purchase unit snapshot is immutable');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0017_purchase_line_unit_snapshot', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
