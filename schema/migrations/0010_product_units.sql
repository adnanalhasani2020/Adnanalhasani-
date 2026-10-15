-- Product unit catalog and per-product conversion configuration.
-- Conversion ratios are exact positive rationals: base-unit quantity per one configured unit.
CREATE TABLE measurement_units (
    unit_id TEXT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE COLLATE NOCASE,
    name TEXT NOT NULL,
    dimension TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (typeof(version_no) = 'integer' AND version_no > 0),
    CHECK (length(trim(code)) > 0),
    CHECK (length(trim(name)) > 0),
    CHECK (length(trim(dimension)) > 0)
);

CREATE TABLE product_unit_settings (
    product_unit_setting_id TEXT PRIMARY KEY,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    unit_id TEXT NOT NULL REFERENCES measurement_units(unit_id),
    role TEXT NOT NULL CHECK (role IN ('base','allowed')),
    conversion_numerator INTEGER NOT NULL CHECK (typeof(conversion_numerator) = 'integer' AND conversion_numerator > 0),
    conversion_denominator INTEGER NOT NULL CHECK (typeof(conversion_denominator) = 'integer' AND conversion_denominator > 0),
    state TEXT NOT NULL CHECK (state IN ('active','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    configured_by_ref TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (product_id, unit_id),
    CHECK (role <> 'base' OR (conversion_numerator = 1 AND conversion_denominator = 1))
);

CREATE UNIQUE INDEX idx_product_unit_one_active_base
ON product_unit_settings(product_id)
WHERE role = 'base' AND state = 'active';

CREATE INDEX idx_product_unit_settings_product_state
ON product_unit_settings(product_id, state, role);

CREATE TRIGGER trg_product_unit_allowed_requires_base
BEFORE INSERT ON product_unit_settings
WHEN NEW.role = 'allowed' AND NOT EXISTS (
    SELECT 1 FROM product_unit_settings
    WHERE product_id = NEW.product_id AND role = 'base' AND state = 'active'
)
BEGIN
    SELECT RAISE(ABORT, 'configure a base unit before adding allowed units');
END;

CREATE TRIGGER trg_product_unit_settings_dimension_insert
BEFORE INSERT ON product_unit_settings
BEGIN
    SELECT CASE
        WHEN (SELECT dimension FROM measurement_units WHERE unit_id = NEW.unit_id) IS NULL
        THEN RAISE(ABORT, 'measurement unit does not exist')
    END;
    SELECT CASE
        WHEN (SELECT state FROM measurement_units WHERE unit_id = NEW.unit_id) <> 'active'
        THEN RAISE(ABORT, 'retired measurement unit cannot be configured')
    END;
END;

CREATE TRIGGER trg_product_unit_settings_dimension_guard
BEFORE INSERT ON product_unit_settings
WHEN EXISTS (
    SELECT 1
    FROM product_unit_settings existing
    JOIN measurement_units existing_unit ON existing_unit.unit_id = existing.unit_id
    JOIN measurement_units new_unit ON new_unit.unit_id = NEW.unit_id
    WHERE existing.product_id = NEW.product_id
      AND existing.state = 'active'
      AND existing_unit.dimension <> new_unit.dimension
)
BEGIN
    SELECT RAISE(ABORT, 'product unit dimension must match existing active product units');
END;

CREATE TRIGGER trg_measurement_unit_retire_referenced_guard
BEFORE UPDATE OF state ON measurement_units
WHEN NEW.state = 'retired' AND OLD.state <> 'retired'
BEGIN
    SELECT CASE
        WHEN EXISTS (
            SELECT 1 FROM product_unit_settings
            WHERE unit_id = OLD.unit_id AND state = 'active'
        )
        THEN RAISE(ABORT, 'measurement unit is configured for a product')
    END;
END;

-- Mirror the application invariants on updates as well as inserts. This prevents
-- direct SQL writers from bypassing unit state, base-first, and dimension rules.
CREATE TRIGGER trg_product_unit_allowed_requires_base_update
BEFORE UPDATE OF product_id, unit_id, role, state ON product_unit_settings
WHEN NEW.state = 'active'
 AND NEW.role = 'allowed'
 AND NOT EXISTS (
    SELECT 1 FROM product_unit_settings base
    WHERE base.product_id = NEW.product_id
      AND base.role = 'base'
      AND base.state = 'active'
      AND base.product_unit_setting_id <> OLD.product_unit_setting_id
 )
BEGIN
    SELECT RAISE(ABORT, 'configure a base unit before adding allowed units');
END;

CREATE TRIGGER trg_product_unit_settings_update_guard
BEFORE UPDATE OF product_id, unit_id, role, state ON product_unit_settings
WHEN NEW.state = 'active'
BEGIN
    SELECT CASE
        WHEN (SELECT state FROM measurement_units WHERE unit_id = NEW.unit_id) IS NULL
        THEN RAISE(ABORT, 'measurement unit does not exist')
    END;
    SELECT CASE
        WHEN (SELECT state FROM measurement_units WHERE unit_id = NEW.unit_id) <> 'active'
        THEN RAISE(ABORT, 'retired measurement unit cannot be configured')
    END;
    SELECT CASE
        WHEN EXISTS (
            SELECT 1
            FROM product_unit_settings existing
            JOIN measurement_units existing_unit ON existing_unit.unit_id = existing.unit_id
            JOIN measurement_units new_unit ON new_unit.unit_id = NEW.unit_id
            WHERE existing.product_id = NEW.product_id
              AND existing.state = 'active'
              AND existing.product_unit_setting_id <> OLD.product_unit_setting_id
              AND existing_unit.dimension <> new_unit.dimension
        )
        THEN RAISE(ABORT, 'product unit dimension must match existing active product units')
    END;
END;

CREATE TRIGGER trg_measurement_unit_dimension_update_guard
BEFORE UPDATE OF dimension ON measurement_units
WHEN NEW.dimension <> OLD.dimension
 AND EXISTS (
    SELECT 1 FROM product_unit_settings
    WHERE unit_id = OLD.unit_id
 )
BEGIN
    SELECT RAISE(ABORT, 'cannot change dimension while unit is configured for a product');
END;

-- Configuration is append/retire, not destructive deletion. Keep history and
-- references stable for future transaction snapshots and auditability.
CREATE TRIGGER trg_measurement_units_no_delete
BEFORE DELETE ON measurement_units
BEGIN
    SELECT RAISE(ABORT, 'measurement units cannot be deleted; retire them instead');
END;

CREATE TRIGGER trg_product_unit_settings_no_delete
BEFORE DELETE ON product_unit_settings
BEGIN
    SELECT RAISE(ABORT, 'product unit settings cannot be deleted; retire them instead');
END;
