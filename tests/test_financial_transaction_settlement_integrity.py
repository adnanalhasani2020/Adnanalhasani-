"""Database-level guards for persisted settlement recognition facts."""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database


STAMP = "2026-10-18T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_settlement(db, *, payment_amount=12500, obligation_amount=12500,
                    payment_currency="YER", obligation_currency="YER"):
    creditor, debtor = uid(), uid()
    account, obligation, payment, settlement = uid(), uid(), uid(), uid()
    for person in (creditor, debtor):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,'cash','active',?,?)",
        (account, creditor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at) VALUES(?,?,?,?,?,'open',?,?)",
        (obligation, creditor, debtor, obligation_amount, obligation_currency, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at) VALUES(?,?,?,?,?,?,'completed',?,?)",
        (payment, obligation, debtor, creditor, payment_amount, payment_currency, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,settled_at,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?,?)",
        (settlement, payment, obligation, STAMP, STAMP, STAMP),
    )
    db.commit()
    return account, settlement


def insert_transaction(db, account, settlement, *, amount=12500, currency="YER",
                       source_type="settlement", source_ref=None):
    source_ref = source_ref or settlement
    transaction_id = uid()
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,?,?,?,?,'recognized',?,?,?,?,?)",
        (transaction_id, settlement, account, amount, currency, source_type, source_ref, STAMP, STAMP, STAMP),
    )
    return transaction_id


def test_database_accepts_a_consistent_fully_settled_finance_transaction():
    db = connect_database()
    account, settlement = seed_settlement(db)
    insert_transaction(db, account, settlement)
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 1
    db.close()


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"amount": 5000}, "fully settled payment"),
        ({"currency": "USD"}, "fully settled payment"),
        ({"source_ref": "not-the-settlement"}, "fully settled payment"),
        ({"source_type": "correction"}, "fully settled payment"),
    ],
)
def test_database_rejects_settlement_ledger_facts_that_do_not_match_source(overrides, message):
    db = connect_database()
    account, settlement = seed_settlement(db)
    with pytest.raises(sqlite3.IntegrityError, match=message):
        insert_transaction(db, account, settlement, **overrides)
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    db.close()


def test_database_rejects_settlement_transaction_for_partial_payment():
    db = connect_database()
    account, settlement = seed_settlement(db, payment_amount=5000, obligation_amount=12500)
    with pytest.raises(sqlite3.IntegrityError, match="fully settled payment"):
        insert_transaction(db, account, settlement)
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    db.close()
