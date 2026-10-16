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

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0019_supplier_invoice_database_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
