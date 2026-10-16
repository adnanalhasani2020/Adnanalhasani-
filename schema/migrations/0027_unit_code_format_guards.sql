-- Keep the shared unit catalog's identifier format consistent even for direct SQL writers.
-- Application writes already uppercase and trim codes and reject whitespace.
CREATE TRIGGER trg_measurement_unit_code_format_insert
BEFORE INSERT ON measurement_units
WHEN NEW.code <> upper(trim(NEW.code))
  OR instr(NEW.code, ' ') > 0
  OR instr(NEW.code, char(9)) > 0
  OR instr(NEW.code, char(10)) > 0
  OR instr(NEW.code, char(11)) > 0
  OR instr(NEW.code, char(12)) > 0
  OR instr(NEW.code, char(13)) > 0
BEGIN
    SELECT RAISE(ABORT, 'unit code must be uppercase, trimmed, and contain no whitespace');
END;

CREATE TRIGGER trg_measurement_unit_code_format_update
BEFORE UPDATE OF code ON measurement_units
WHEN NEW.code <> upper(trim(NEW.code))
  OR instr(NEW.code, ' ') > 0
  OR instr(NEW.code, char(9)) > 0
  OR instr(NEW.code, char(10)) > 0
  OR instr(NEW.code, char(11)) > 0
  OR instr(NEW.code, char(12)) > 0
  OR instr(NEW.code, char(13)) > 0
BEGIN
    SELECT RAISE(ABORT, 'unit code must be uppercase, trimmed, and contain no whitespace');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0027_unit_code_format_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
