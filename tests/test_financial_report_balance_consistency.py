"""Cross-check the account activity report against the persisted balance projection."""

from uuid import uuid4

from agent_core.financial_balance_read import FinancialBalanceReader
from agent_core.financial_ledger_activity_report import FinancialLedgerActivityReportReader
from agent_core.persistence import connect_database


def _uid():
    return str(uuid4())


def _create_account(db):
    person_id, account_id = _uid(), _uid()
    stamp = "2026-10-01T00:00:00Z"
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (person_id, stamp, stamp),
    )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (account_id, person_id, "general", stamp, stamp),
    )
    db.commit()
    return account_id


def _post(db, account_id, amount, posted_at, currency="YER"):
    transaction_id, entry_id = _uid(), _uid()
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,NULL,?,?,?,'recognized','correction',?,?,?,?)",
        (transaction_id, account_id, amount, currency, _uid(), posted_at, posted_at, posted_at),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
        (entry_id, account_id, transaction_id, amount, currency, posted_at, posted_at, posted_at),
    )
    db.commit()


def test_activity_report_closing_balance_matches_balance_reader_after_database_reopen(tmp_path):
    path = tmp_path / "finance-report-consistency.sqlite"
    db = connect_database(path)
    account_id = _create_account(db)
    _post(db, account_id, 2000, "2026-10-01T09:00:00Z")
    _post(db, account_id, -350, "2026-10-03T12:00:00Z")
    _post(db, account_id, 125, "2026-10-05T08:30:00Z")
    _post(db, account_id, 9999, "2026-10-11T00:00:00Z")
    before = db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0]
    db.close()

    # Reopen the persisted database to ensure the assertion is not relying on
    # uncommitted setup state or a connection-local projection.
    db = connect_database(path)
    report = FinancialLedgerActivityReportReader().read(
        db,
        account_id,
        "YER",
        start_at="2026-10-03T00:00:00Z",
        end_at="2026-10-10T00:00:00Z",
    )
    balance = FinancialBalanceReader().read(
        db, account_id, "YER", as_of="2026-10-10T00:00:00Z"
    )

    assert report.opening_balance_minor == 2000
    assert report.activity_total_minor == -225
    assert report.closing_balance_minor == 1775
    assert balance.amount_minor == report.closing_balance_minor
    assert balance.entry_count == 3
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == before
    db.close()
