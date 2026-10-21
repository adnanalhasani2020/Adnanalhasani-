-- Commercial cash/credit terms and append-only cash receipts.
-- This is an operational receivables register, not a general ledger or tax invoice.

CREATE TABLE commercial_sale_terms (
    sale_id TEXT PRIMARY KEY REFERENCES sales(sale_id),
    buyer_person_id TEXT NOT NULL REFERENCES persons(person_id),
    payment_method TEXT NOT NULL CHECK (payment_method IN ('cash','credit')),
    total_minor INTEGER NOT NULL CHECK (typeof(total_minor)='integer' AND total_minor >= 0),
    currency_code TEXT NOT NULL CHECK (currency_code IN ('SAR','YER','USD')),
    created_at TEXT NOT NULL
);

CREATE INDEX ix_commercial_sale_terms_buyer ON commercial_sale_terms(buyer_person_id, created_at);

CREATE TABLE commercial_sale_payments (
    payment_id TEXT PRIMARY KEY,
    sale_id TEXT NOT NULL REFERENCES commercial_sale_terms(sale_id),
    buyer_person_id TEXT NOT NULL REFERENCES persons(person_id),
    amount_minor INTEGER NOT NULL CHECK (typeof(amount_minor)='integer' AND amount_minor > 0),
    currency_code TEXT NOT NULL CHECK (currency_code IN ('SAR','YER','USD')),
    method TEXT NOT NULL CHECK (method='cash'),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) BETWEEN 1 AND 200),
    request_hash TEXT NOT NULL,
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (buyer_person_id, idempotency_key)
);

CREATE INDEX ix_commercial_sale_payments_sale ON commercial_sale_payments(sale_id, created_at);

CREATE TRIGGER commercial_sale_terms_context_guard
BEFORE INSERT ON commercial_sale_terms
WHEN NOT EXISTS (
    SELECT 1 FROM sales s
    WHERE s.sale_id=NEW.sale_id
      AND s.state IN ('completed','fulfilled')
)
BEGIN
    SELECT RAISE(ABORT, 'commercial payment terms require a fulfilled sale');
END;

CREATE TRIGGER commercial_sale_terms_immutable_update
BEFORE UPDATE ON commercial_sale_terms
BEGIN
    SELECT RAISE(ABORT, 'commercial sale terms are immutable');
END;

CREATE TRIGGER commercial_sale_terms_immutable_delete
BEFORE DELETE ON commercial_sale_terms
BEGIN
    SELECT RAISE(ABORT, 'commercial sale terms are immutable');
END;

CREATE TRIGGER commercial_sale_payment_context_guard
BEFORE INSERT ON commercial_sale_payments
WHEN NOT EXISTS (
    SELECT 1 FROM commercial_sale_terms t
    WHERE t.sale_id=NEW.sale_id
      AND t.buyer_person_id=NEW.buyer_person_id
      AND t.currency_code=NEW.currency_code
      AND NEW.amount_minor <= t.total_minor - COALESCE((
          SELECT SUM(p.amount_minor) FROM commercial_sale_payments p
          WHERE p.sale_id=NEW.sale_id
      ),0)
)
BEGIN
    SELECT RAISE(ABORT, 'payment must belong to the sale buyer and cannot exceed the outstanding balance');
END;

CREATE TRIGGER commercial_sale_payment_immutable_update
BEFORE UPDATE ON commercial_sale_payments
BEGIN
    SELECT RAISE(ABORT, 'commercial sale payments are immutable');
END;

CREATE TRIGGER commercial_sale_payment_immutable_delete
BEFORE DELETE ON commercial_sale_payments
BEGIN
    SELECT RAISE(ABORT, 'commercial sale payments are immutable');
END;
