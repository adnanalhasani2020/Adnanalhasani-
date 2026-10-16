"""Tests for the read-only financial ledger activity report."""
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.financial_ledger_activity_report import FinancialLedgerActivityReportReader
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def uid():
    return str(uuid4())


def account(db):
    person_id, account_id = uid(), uid()
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


def ledger(db, account_id, amount, currency, posted_at, *, recognized_at=None, source_ref=None):
    tx_id, entry_id = uid(), uid()
    recognized_at = recognized_at or posted_at
    source_ref = source_ref or uid()
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,NULL,?,?,?,'recognized','correction',?,?,?,?)",
        (tx_id, account_id, amount, currency, source_ref, recognized_at, recognized_at, recognized_at),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
        (entry_id, account_id, tx_id, amount, currency, posted_at, posted_at, posted_at),
    )
    db.commit()
    return tx_id, entry_id


def test_report_calculates_opening_activity_and_closing_balance_for_half_open_period():
    db = connect_database()
    account_id = account(db)
    _, opening_id = ledger(db, account_id, 1500, "YER", "2026-10-01T09:00:00Z")
    _, first_id = ledger(db, account_id, -200, "YER", "2026-10-03T10:00:00Z")
    _, second_id = ledger(db, account_id, 450, "YER", "2026-10-05T10:00:00Z")
    ledger(db, account_id, 999, "YER", "2026-10-10T00:00:00Z")  # end is exclusive
    before_count = db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0]

    report = FinancialLedgerActivityReportReader().read(
        db, account_id, "YER",
        start_at="2026-10-03T00:00:00Z",
        end_at="2026-10-10T00:00:00Z",
    )

    assert report.opening_balance_minor == 1500
    assert report.activity_total_minor == 250
    assert report.closing_balance_minor == 1750
    assert report.entry_count == 2
    assert [str(line.ledger_entry_id) for line in report.lines] == [first_id, second_id]
    assert all(line.ledger_entry_id != opening_id for line in report.lines)
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == before_count
    db.close()


def test_report_separates_currencies_and_normalizes_offset_aware_period():
    db = connect_database()
    account_id = account(db)
    ledger(db, account_id, 300, "YER", "2026-10-01T10:00:00Z")
    ledger(db, account_id, 700, "USD", "2026-10-01T10:00:00Z")

    report = FinancialLedgerActivityReportReader().read(
        db, account_id, "YER",
        start_at="2026-10-01T12:00:00+02:00",
        end_at="2026-10-02T00:00:00+02:00",
    )

    assert report.opening_balance_minor == 0
    assert report.activity_total_minor == 300
    assert report.closing_balance_minor == 300
    assert report.currency_code == "YER"
    assert report.start_at == datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc)
    db.close()


def test_report_empty_period_returns_zero_balances_without_writes():
    db = connect_database()
    account_id = account(db)
    before = db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0]

    report = FinancialLedgerActivityReportReader().read(
        db, account_id, "SAR",
        start_at="2026-10-01T00:00:00Z",
        end_at="2026-10-02T00:00:00Z",
    )

    assert report.lines == ()
    assert report.entry_count == 0
    assert report.opening_balance_minor == 0
    assert report.closing_balance_minor == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == before
    db.close()


@pytest.mark.parametrize(
    ("account_id", "currency", "start_at", "end_at", "message"),
    [
        ("not-a-uuid", "YER", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", "valid UUID"),
        (str(uuid4()), "yer", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", "3-letter uppercase"),
        (str(uuid4()), "YER", datetime(2026, 10, 1), "2026-10-02T00:00:00Z", "timezone-aware"),
        (str(uuid4()), "YER", "2026-10-02T00:00:00Z", "2026-10-02T00:00:00Z", "earlier than"),
    ],
)
def test_report_rejects_invalid_requests(account_id, currency, start_at, end_at, message):
    db = connect_database()
    known_account = account(db)
    if message == "timezone-aware":
        account_id = known_account
    if message == "earlier than":
        account_id = known_account
    with pytest.raises(ValidationError, match=message):
        FinancialLedgerActivityReportReader().read(
            db, account_id, currency, start_at=start_at, end_at=end_at
        )
    db.close()


def test_report_rejects_unknown_account():
    db = connect_database()
    with pytest.raises(ValidationError, match="does not exist"):
        FinancialLedgerActivityReportReader().read(
            db, str(uuid4()), "YER",
            start_at="2026-10-01T00:00:00Z",
            end_at="2026-10-02T00:00:00Z",
        )
    db.close()



def test_report_fails_closed_on_orphaned_posted_entry_in_opening_history():
    db = connect_database()
    account_id = account(db)
    stamp = "2026-10-01T09:00:00Z"
    db.execute("PRAGMA foreign_keys = OFF")
    db.execute("DROP TRIGGER ledger_entry_currency_matches_transaction_insert")
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
        (uid(), account_id, uid(), 800, "YER", stamp, stamp, stamp),
    )
    db.commit()

    with pytest.raises(ValidationError, match="missing Financial Transaction"):
        FinancialLedgerActivityReportReader().read(
            db, account_id, "YER",
            start_at="2026-10-03T00:00:00Z",
            end_at="2026-10-10T00:00:00Z",
        )

    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 1
    db.close()


def test_report_fails_closed_on_posted_entry_with_void_transaction():
    db = connect_database()
    account_id = account(db)
    tx_id = uid()
    stamp = "2026-10-01T09:00:00Z"
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,NULL,?,500,'YER','void','correction',?,?,?,?)",
        (tx_id, account_id, uid(), stamp, stamp, stamp),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
        (uid(), account_id, tx_id, 500, "YER", stamp, stamp, stamp),
    )
    db.commit()

    with pytest.raises(ValidationError, match="does not match a recognized"):
        FinancialLedgerActivityReportReader().read(
            db, account_id, "YER",
            start_at="2026-10-03T00:00:00Z",
            end_at="2026-10-10T00:00:00Z",
        )

    assert db.execute(
        "SELECT state FROM financial_transactions WHERE financial_transaction_id=?", (tx_id,)
    ).fetchone()[0] == "void"
    db.close()
