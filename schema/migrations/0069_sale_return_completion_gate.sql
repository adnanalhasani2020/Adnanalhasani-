-- A return request reserves quantity before the external refund is sent.
-- Inventory can be compensated only after the return has a durable completion record.
CREATE TABLE sale_return_completions (
    return_event_id TEXT PRIMARY KEY REFERENCES sale_return_events(return_event_id),
    completed_at TEXT NOT NULL,
    credit_note_id TEXT NULL UNIQUE REFERENCES credit_notes(credit_note_id),
    state TEXT NOT NULL CHECK (state='completed')
);

-- Existing return movements represent completed returns; preserve their state across upgrade.
INSERT INTO sale_return_completions(return_event_id,completed_at,credit_note_id,state)
SELECT re.return_event_id,MAX(m.created_at),cn.credit_note_id,'completed'
FROM sale_return_events re
JOIN sale_return_lines rl ON rl.return_event_id=re.return_event_id
JOIN inventory_movements m ON m.source_type='sale_return' AND m.source_ref=rl.sale_return_line_id
LEFT JOIN credit_notes cn ON cn.return_event_id=re.return_event_id
GROUP BY re.return_event_id,cn.credit_note_id;

CREATE TRIGGER sale_return_completion_immutable_update
BEFORE UPDATE ON sale_return_completions
BEGIN
    SELECT RAISE(ABORT, 'Sale return completion is immutable');
END;
CREATE TRIGGER sale_return_completion_immutable_delete
BEFORE DELETE ON sale_return_completions
BEGIN
    SELECT RAISE(ABORT, 'Sale return completion is immutable');
END;

CREATE TRIGGER inventory_sale_return_requires_completion
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type='sale_return'
 AND NOT EXISTS (
    SELECT 1
    FROM sale_return_lines rl
    JOIN sale_return_completions c ON c.return_event_id=rl.return_event_id
    WHERE rl.sale_return_line_id=NEW.source_ref
      AND c.state='completed'
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale return inventory movement requires a completed return event');
END;
