-- Supplier-side invoice capture, reconciled against purchase receipt evidence.
CREATE TABLE supplier_invoices (
    supplier_invoice_id TEXT PRIMARY KEY,
    purchase_id TEXT NOT NULL REFERENCES purchases(purchase_id),
    supplier_ref TEXT NOT NULL CHECK (length(trim(supplier_ref)) > 0),
    invoice_number TEXT NOT NULL CHECK (length(trim(invoice_number)) > 0),
    currency_code TEXT NOT NULL CHECK (
        length(currency_code) = 3 AND currency_code = upper(currency_code)
        AND currency_code GLOB '[A-Z][A-Z][A-Z]'
    ),
    subtotal_minor INTEGER NOT NULL CHECK (subtotal_minor >= 0),
    tax_minor INTEGER NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    freight_minor INTEGER NOT NULL DEFAULT 0 CHECK (freight_minor >= 0),
    total_minor INTEGER NOT NULL CHECK (total_minor >= 0 AND total_minor = subtotal_minor + tax_minor + freight_minor),
    state TEXT NOT NULL CHECK (state IN ('recorded','void')),
    recorded_at TEXT NOT NULL,
    recorded_by_ref TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    request_fingerprint TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    UNIQUE (supplier_ref, invoice_number),
    UNIQUE (supplier_invoice_id, purchase_id)
);

CREATE TABLE supplier_invoice_lines (
    supplier_invoice_line_id TEXT PRIMARY KEY,
    supplier_invoice_id TEXT NOT NULL,
    purchase_id TEXT NOT NULL,
    purchase_line_id TEXT NOT NULL,
    quantity_units INTEGER NOT NULL CHECK (quantity_units > 0),
    unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
    line_total_minor INTEGER NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor = quantity_units * unit_price_minor),
    FOREIGN KEY (supplier_invoice_id, purchase_id)
        REFERENCES supplier_invoices(supplier_invoice_id, purchase_id),
    FOREIGN KEY (purchase_line_id, purchase_id)
        REFERENCES purchase_lines(purchase_line_id, purchase_id),
    UNIQUE (supplier_invoice_id, purchase_line_id)
);

CREATE INDEX idx_supplier_invoices_purchase_state ON supplier_invoices(purchase_id, state);
CREATE INDEX idx_supplier_invoice_lines_purchase_line ON supplier_invoice_lines(purchase_line_id, purchase_id);

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0009_supplier_invoices', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
