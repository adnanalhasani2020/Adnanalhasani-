"""Regression test for orphaned posted ledger entries in a legacy/corrupt database."""
import uuid

import pytest

from agent_core.financial_balance_read import FinancialBalanceReader
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def test_balance_fails_closed_on_posted_entry_with_missing_transaction():
    db = connect_database()
    person_id, account_id = str(uuid.uuid4()), str(uuid.uuid4())
    stamp = "2026-10-10T00:00:00Z"
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

    # Reproduce a legacy database written with FK enforcement and the matching
    # insert guard absent. Current schema correctly prevents creating this row.
    db.execute("PRAGMA foreign_keys = OFF")
    db.execute("DROP TRIGGER ledger_entry_currency_matches_transaction_insert")
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
        (str(uuid.uuid4()), account_id, str(uuid.uuid4()), 500, "YER", stamp, stamp, stamp),
    )
    db.commit()

    with pytest.raises(ValidationError, match="missing Financial Transaction"):
        FinancialBalanceReader().read(db, account_id, "YER", as_of=stamp)

    # The reader is read-only: it reports corruption without deleting or repairing it.
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 1
    db.close()
