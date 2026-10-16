-- Keep supplier invoice headers and lines bound to the same Purchase.
-- Migration 0019 is already deployed; these additional guards belong in a new
-- migration so existing databases receive them safely.
CREATE TRIGGER trg_supplier_invoice_line_matches_header_purchase_insert
BEFORE INSERT ON supplier_invoice_lines
WHEN NOT EXISTS (
    SELECT 1
    FROM supplier_invoices invoice
    WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
      AND invoice.purchase_id = NEW.purchase_id
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice line Purchase must match its invoice header');
END;

CREATE TRIGGER trg_supplier_invoice_line_matches_header_purchase_update
BEFORE UPDATE OF supplier_invoice_id, purchase_id ON supplier_invoice_lines
WHEN NOT EXISTS (
    SELECT 1
    FROM supplier_invoices invoice
    WHERE invoice.supplier_invoice_id = NEW.supplier_invoice_id
      AND invoice.purchase_id = NEW.purchase_id
)
BEGIN
    SELECT RAISE(ABORT, 'supplier invoice line Purchase must match its invoice header');
END;

-- Do not allow a header's Purchase to diverge from lines already attached to it.
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

-- Currency is the invoice-level currency; preserve compatibility with every
-- attached purchase line that has a recorded baseline unit cost.
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
VALUES ('0021_supplier_invoice_header_line_consistency', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
