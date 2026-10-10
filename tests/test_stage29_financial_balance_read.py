"""Integration tests for the derived, read-only persisted Finance Balance."""
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.financial_balance_read import FinancialBalanceReader
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def uid():
    return str(uuid4())


def account(db):
    person_id, account_id = uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (person_id, "2026-10-10T00:00:00Z", "2026-10-10T00:00:00Z"),
    )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (account_id, person_id, "general", "2026-10-10T00:00:00Z", "2026-10-10T00:00:00Z"),
    )
    db.commit()
    return account_id


def ledger(db, account_id, amount, currency, posted_at, *, tx_state="recognized", entry_state="posted"):
    transaction_id, entry_id = uid(), uid()
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,NULL,?,?,?,?, 'correction',?,?,?,?)",
        (transaction_id, account_id, amount, currency, tx_state, uid(), posted_at, posted_at, posted_at),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,amount_minor,"
        "currency_code,entry_sequence,state,posted_at,created_at,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?)",
        (entry_id, account_id, transaction_id, amount, currency, entry_state, posted_at, posted_at, posted_at),
    )
    db.commit()
    return transaction_id, entry_id


def test_balance_derives_only_recognized_posted_entries_for_requested_currency_and_as_of():
    db = connect_database()
    account_id = account(db)
    ledger(db, account_id, 1250, "YER", "2026-10-01T10:00:00Z")
    ledger(db, account_id, -200, "YER", "2026-10-05T10:00:00Z")
    ledger(db, account_id, 900, "YER", "2026-10-08T10:00:00Z", entry_state="void")
    ledger(db, account_id, 400, "YER", "2026-10-09T10:00:00Z", tx_state="void")
    ledger(db, account_id, 700, "USD", "2026-10-09T10:00:00Z")

    before = FinancialBalanceReader().read(
        db, account_id, "YER", as_of="2026-10-03T10:00:00+00:00"
    )
    current = FinancialBalanceReader().read(
        db, account_id, "YER", as_of="2026-10-10T00:00:00Z"
    )
    usd = FinancialBalanceReader().read(
        db, account_id, "USD", as_of="2026-10-10T00:00:00Z"
    )

    assert before.amount_minor == 1250
    assert before.entry_count == 1
    assert current.amount_minor == 1050
    assert current.entry_count == 2
    assert usd.amount_minor == 700
    assert usd.entry_count == 1
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 5
    db.close()


def test_balance_zero_when_no_recognized_posted_entries_and_does_not_write():
    db = connect_database()
    account_id = account(db)
    before = db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0]
    result = FinancialBalanceReader().read(db, account_id, "YER", as_of="2026-10-10T00:00:00Z")
    after = db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0]
    assert result.amount_minor == 0
    assert result.entry_count == 0
    assert before == after == 0
    db.close()


@pytest.mark.parametrize(
    ("account_id", "currency", "as_of", "message"),
    [
        ("not-a-uuid", "YER", "2026-10-10T00:00:00Z", "valid UUID"),
        (str(uuid4()), "YER", "2026-10-10T00:00:00Z", "does not exist"),
        (str(uuid4()), "", "2026-10-10T00:00:00Z", "currency_code is required"),
        (str(uuid4()), "YER", datetime(2026, 10, 10), "timezone-aware"),
    ],
)
def test_balance_rejects_invalid_request(account_id, currency, as_of, message):
    connection = connect_database()
    known_account = account(connection)
    if message == "timezone-aware":
        account_id = known_account
    with pytest.raises(ValidationError, match=message):
        FinancialBalanceReader().read(connection, account_id, currency, as_of=as_of)
    connection.close()


def test_balance_fails_closed_on_ledger_transaction_currency_mismatch():
    db = connect_database()
    account_id = account(db)
    ledger(db, account_id, 100, "YER", "2026-10-01T10:00:00Z")
    # Corrupt persisted data: entry currency differs from the recognized transaction.
    db.execute("UPDATE ledger_entries SET currency_code='USD'")
    db.commit()
    with pytest.raises(ValidationError, match="does not match"):
        FinancialBalanceReader().read(db, account_id, "YER", as_of="2026-10-10T00:00:00Z")
    db.close()
