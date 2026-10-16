-- Add opening balances as recognized Finance transactions.
-- Rebuild related tables without disabling foreign-key enforcement.

CREATE TABLE financial_transactions_new (
    financial_transaction_id TEXT PRIMARY KEY,
    settlement_id TEXT NULL REFERENCES settlements(settlement_id),
    financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    currency_code TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('recognized','void')),
    recognition_source_type TEXT NOT NULL,
    recognition_source_ref TEXT NOT NULL,
    recognized_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
    CHECK (
        (settlement_id IS NOT NULL AND recognition_source_type = 'settlement')
        OR
        (settlement_id IS NULL AND recognition_source_type IN ('reversal','correction','opening_balance'))
    )
);

CREATE TABLE ledger_entries_new (
    ledger_entry_id TEXT PRIMARY KEY,
    financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
    financial_transaction_id TEXT NOT NULL REFERENCES financial_transactions_new(financial_transaction_id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
    currency_code TEXT NOT NULL,
    entry_sequence INTEGER NOT NULL CHECK (entry_sequence > 0),
    state TEXT NOT NULL CHECK (state IN ('posted','void')),
    posted_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    provenance_id TEXT NULL,
    UNIQUE (financial_transaction_id, entry_sequence)
);

INSERT INTO financial_transactions_new (
    financial_transaction_id, settlement_id, financial_account_id, amount_minor, currency_code,
    state, recognition_source_type, recognition_source_ref, recognized_at, created_at, updated_at,
    provenance_id, version_no
)
SELECT financial_transaction_id, settlement_id, financial_account_id, amount_minor, currency_code,
       state, recognition_source_type, recognition_source_ref, recognized_at, created_at, updated_at,
       provenance_id, version_no
FROM financial_transactions;

INSERT INTO ledger_entries_new (
    ledger_entry_id, financial_account_id, financial_transaction_id, amount_minor, currency_code,
    entry_sequence, state, posted_at, created_at, updated_at, provenance_id
)
SELECT ledger_entry_id, financial_account_id, financial_transaction_id, amount_minor, currency_code,
       entry_sequence, state, posted_at, created_at, updated_at, provenance_id
FROM ledger_entries;

DROP TABLE ledger_entries;
DROP TABLE financial_transactions;

ALTER TABLE financial_transactions_new RENAME TO financial_transactions;
ALTER TABLE ledger_entries_new RENAME TO ledger_entries;

CREATE UNIQUE INDEX uq_financial_transactions_settlement
    ON financial_transactions(settlement_id)
    WHERE settlement_id IS NOT NULL;
CREATE UNIQUE INDEX uq_financial_transactions_source
    ON financial_transactions(recognition_source_type, recognition_source_ref);
CREATE INDEX idx_financial_transactions_account_time
    ON financial_transactions(financial_account_id, recognized_at);
CREATE UNIQUE INDEX uq_financial_transactions_opening_balance_account_currency
    ON financial_transactions(financial_account_id, currency_code)
    WHERE recognition_source_type = 'opening_balance';
CREATE INDEX idx_ledger_account_time
    ON ledger_entries(financial_account_id, posted_at, entry_sequence);
CREATE INDEX idx_ledger_transaction ON ledger_entries(financial_transaction_id);
