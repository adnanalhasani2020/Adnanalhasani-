PRAGMA foreign_keys = ON;

CREATE TRIGGER prevent_person_canonical_cycles
BEFORE UPDATE OF canonical_person_id ON persons
FOR EACH ROW
WHEN NEW.canonical_person_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'canonical person merge cycle')
    WHERE EXISTS (
        WITH RECURSIVE chain(person_id) AS (
            SELECT NEW.canonical_person_id
            UNION ALL
            SELECT p.canonical_person_id
            FROM persons AS p
            JOIN chain AS c ON p.person_id = c.person_id
            WHERE p.canonical_person_id IS NOT NULL
        )
        SELECT 1 FROM chain WHERE person_id = NEW.person_id
    );
END;
