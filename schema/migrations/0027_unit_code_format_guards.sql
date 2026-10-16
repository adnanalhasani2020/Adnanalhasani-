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
  OR instr(NEW.code, char(28)) > 0
  OR instr(NEW.code, char(29)) > 0
  OR instr(NEW.code, char(30)) > 0
  OR instr(NEW.code, char(31)) > 0
  OR instr(NEW.code, char(133)) > 0
  OR instr(NEW.code, char(160)) > 0
  OR instr(NEW.code, char(5760)) > 0
  OR instr(NEW.code, char(8192)) > 0
  OR instr(NEW.code, char(8193)) > 0
  OR instr(NEW.code, char(8194)) > 0
  OR instr(NEW.code, char(8195)) > 0
  OR instr(NEW.code, char(8196)) > 0
  OR instr(NEW.code, char(8197)) > 0
  OR instr(NEW.code, char(8198)) > 0
  OR instr(NEW.code, char(8199)) > 0
  OR instr(NEW.code, char(8200)) > 0
  OR instr(NEW.code, char(8201)) > 0
  OR instr(NEW.code, char(8202)) > 0
  OR instr(NEW.code, char(8232)) > 0
  OR instr(NEW.code, char(8233)) > 0
  OR instr(NEW.code, char(8239)) > 0
  OR instr(NEW.code, char(8287)) > 0
  OR instr(NEW.code, char(12288)) > 0
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
  OR instr(NEW.code, char(28)) > 0
  OR instr(NEW.code, char(29)) > 0
  OR instr(NEW.code, char(30)) > 0
  OR instr(NEW.code, char(31)) > 0
  OR instr(NEW.code, char(133)) > 0
  OR instr(NEW.code, char(160)) > 0
  OR instr(NEW.code, char(5760)) > 0
  OR instr(NEW.code, char(8192)) > 0
  OR instr(NEW.code, char(8193)) > 0
  OR instr(NEW.code, char(8194)) > 0
  OR instr(NEW.code, char(8195)) > 0
  OR instr(NEW.code, char(8196)) > 0
  OR instr(NEW.code, char(8197)) > 0
  OR instr(NEW.code, char(8198)) > 0
  OR instr(NEW.code, char(8199)) > 0
  OR instr(NEW.code, char(8200)) > 0
  OR instr(NEW.code, char(8201)) > 0
  OR instr(NEW.code, char(8202)) > 0
  OR instr(NEW.code, char(8232)) > 0
  OR instr(NEW.code, char(8233)) > 0
  OR instr(NEW.code, char(8239)) > 0
  OR instr(NEW.code, char(8287)) > 0
  OR instr(NEW.code, char(12288)) > 0
BEGIN
    SELECT RAISE(ABORT, 'unit code must be uppercase, trimmed, and contain no whitespace');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0027_unit_code_format_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
