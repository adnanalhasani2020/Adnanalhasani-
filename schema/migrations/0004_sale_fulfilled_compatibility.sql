-- Stage 16 compatibility bridge.
-- Keep historical sales.state values untouched; lifecycle_state is the canonical
-- business vocabulary for new writes. Existing rows remain NULL until an ordinary
-- state transition occurs, and readers must interpret legacy state='completed'
-- as the same commercial meaning as lifecycle_state='fulfilled'.
ALTER TABLE sales
    ADD COLUMN lifecycle_state TEXT NULL
    CHECK (lifecycle_state IN ('initiated','confirmed','fulfilled','cancelled','returned'));

CREATE TRIGGER sales_lifecycle_state_after_insert
AFTER INSERT ON sales
BEGIN
    UPDATE sales
       SET lifecycle_state = CASE NEW.state
           WHEN 'completed' THEN 'fulfilled'
           ELSE NEW.state
       END
     WHERE sale_id = NEW.sale_id;
END;

CREATE TRIGGER sales_lifecycle_state_after_state_update
AFTER UPDATE OF state ON sales
BEGIN
    UPDATE sales
       SET lifecycle_state = CASE NEW.state
           WHEN 'completed' THEN 'fulfilled'
           ELSE NEW.state
       END
     WHERE sale_id = NEW.sale_id;
END;
