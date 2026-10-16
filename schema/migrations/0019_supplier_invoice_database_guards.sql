-- Defense in depth for supplier invoices: keep direct SQL writers within the
-- same supplier and received-quantity contract enforced by the application.
CREATE TRIGGER trg_supplier_invoice_supplier_matches_purchase_insert
BEFORE INSERT ON supplier_invoices
WHEN NOT EXISTS (
    SELECT 1 FROM purchases
    WHERE purchase_id = NEW.purchase_id
      AND supplier_ref = NEW.supplier_ref
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice supplier must match its Purchase');
END;

CREATE TRIGGER trg_supplier_invoice_supplier_matches_purchase_update
BEFORE UPDATE OF purchase_id, supplier_ref ON supplier_invoices
WHEN NOT EXISTS (
    SELECT 1 FROM purchases
    WHERE purchase_id = NEW.purchase_id
      AND supplier_ref = NEW.supplier_ref
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice supplier must match its Purchase');
END;

CREATE TRIGGER trg_supplier_invoice_line_received_quantity_guard_insert
BEFORE INSERT ON supplier_invoice_lines
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM supplier_invoices invoice
            WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
              AND invoice.purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice line Purchase must match its invoice header')
    END;

    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM purchase_lines
            WHERE purchase_line_id = NEW.purchase_line_id
              AND purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice line must reference a line from its Purchase')
    END;

    SELECT CASE
        WHEN EXISTS (
            SELECT 1
            FROM supplier_invoices invoice
            JOIN purchase_lines line
              ON line.purchase_line_id = NEW.purchase_line_id
             AND line.purchase_id = NEW.purchase_id
            WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
              AND line.unit_cost_minor IS NOT NULL
              AND line.currency_code <> invoice.currency_code
        )
        THEN RAISE(ABORT, 'supplier invoice currency must match its Purchase line currency')
    END;

    SELECT CASE
        WHEN NEW.quantity_units + COALESCE((
            SELECT SUM(existing_line.quantity_units)
            FROM supplier_invoice_lines existing_line
            JOIN supplier_invoices existing_invoice
              ON existing_invoice.supplier_invoice_id = existing_line.supplier_invoice_id
            WHERE existing_line.purchase_line_id = NEW.purchase_line_id
              AND existing_line.purchase_id = NEW.purchase_id
              AND existing_invoice.state = 'recorded'
        ), 0) > (
            SELECT received_quantity_units
            FROM purchase_lines
            WHERE purchase_line_id = NEW.purchase_line_id
              AND purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice quantity exceeds uninvoiced received quantity')
    END;
END;

CREATE TRIGGER trg_supplier_invoice_line_received_quantity_guard_update
BEFORE UPDATE OF supplier_invoice_id, purchase_id, purchase_line_id, quantity_units ON supplier_invoice_lines
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM supplier_invoices invoice
            WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
              AND invoice.purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice line Purchase must match its invoice header')
    END;

    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM purchase_lines
            WHERE purchase_line_id = NEW.purchase_line_id
              AND purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice line must reference a line from its Purchase')
    END;

    SELECT CASE
        WHEN EXISTS (
            SELECT 1
            FROM supplier_invoices invoice
            JOIN purchase_lines line
              ON line.purchase_line_id = NEW.purchase_line_id
             AND line.purchase_id = NEW.purchase_id
            WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
              AND line.unit_cost_minor IS NOT NULL
              AND line.currency_code <> invoice.currency_code
        )
        THEN RAISE(ABORT, 'supplier invoice currency must match its Purchase line currency')
    END;

    SELECT CASE
        WHEN NEW.quantity_units + COALESCE((
            SELECT SUM(existing_line.quantity_units)
            FROM supplier_invoice_lines existing_line
            JOIN supplier_invoices existing_invoice
              ON existing_invoice.supplier_invoice_id = existing_line.supplier_invoice_id
            WHERE existing_line.purchase_line_id = NEW.purchase_line_id
              AND existing_line.purchase_id = NEW.purchase_id
              AND existing_invoice.state = 'recorded'
              AND existing_line.supplier_invoice_line_id <> OLD.supplier_invoice_line_id
        ), 0) > (
            SELECT received_quantity_units
            FROM purchase_lines
            WHERE purchase_line_id = NEW.purchase_line_id
              AND purchase_id = NEW.purchase_id
        )
        THEN RAISE(ABORT, 'supplier invoice quantity exceeds uninvoiced received quantity')
    END;
END;


-- A line must belong to the exact invoice header it references, not merely
-- to some valid Purchase. Also prevent header edits from orphaning existing lines.
CREATE TRIGGER trg_supplier_invoice_purchase_matches_existing_lines_update
BEFORE UPDATE OF purchase_id ON supplier_invoices
WHEN EXISTS (
    SELECT 1
    FROM supplier_invoice_lines line
    WHERE line.supplier_invoice_id = OLD.supplier_invoice_id
      AND line.purchase_id <> NEW.purchase_id
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice Purchase cannot diverge from its lines');
END;

CREATE TRIGGER trg_supplier_invoice_currency_matches_existing_lines_update
BEFORE UPDATE OF currency_code ON supplier_invoices
WHEN EXISTS (
    SELECT 1
    FROM supplier_invoice_lines invoice_line
    JOIN purchase_lines purchase_line
      ON purchase_line.purchase_line_id = invoice_line.purchase_line_id
     AND purchase_line.purchase_id = invoice_line.purchase_id
    WHERE invoice_line.supplier_invoice_id = OLD.supplier_invoice_id
      AND purchase_line.unit_cost_minor IS NOT NULL
      AND purchase_line.currency_code <> NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice currency must match its Purchase line currency');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0019_supplier_invoice_database_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
