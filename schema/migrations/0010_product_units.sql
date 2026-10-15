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
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (length(trim(code)) > 0),
    CHECK (length(trim(name)) > 0),
    CHECK (length(trim(dimension)) > 0)
);

CREATE TABLE product_unit_settings (
    product_unit_setting_id TEXT PRIMARY KEY,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    unit_id TEXT NOT NULL REFERENCES measurement_units(unit_id),
    role TEXT NOT NULL CHECK (role IN ('base','allowed')),
    conversion_numerator INTEGER NOT NULL CHECK (conversion_numerator > 0),
    conversion_denominator INTEGER NOT NULL CHECK (conversion_denominator > 0),
    state TEXT NOT NULL CHECK (state IN ('active','retired')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    configured_by_ref TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (product_id, unit_id)
);

CREATE UNIQUE INDEX idx_product_unit_one_active_base
ON product_unit_settings(product_id)
WHERE role = 'base' AND state = 'active';

CREATE INDEX idx_product_unit_settings_product_state
ON product_unit_settings(product_id, state, role);

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
