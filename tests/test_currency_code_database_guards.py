"""Database-level currency-code representation guards."""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database


STAMP = "2026-10-16T00:00:00Z"


def _person(db):
    person_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (person_id, STAMP, STAMP),
    )
    return person_id


@pytest.mark.parametrize("table", ["loans", "obligations", "payments"])
@pytest.mark.parametrize("currency", ["yer", "US", "USDD", "U$D", "éUR", "１２３"])
def test_financial_source_tables_reject_noncanonical_currency_codes(table, currency):
    db = connect_database()
    creditor = _person(db)
    debtor = _person(db)
    row_id = str(uuid.uuid4())

    if table == "loans":
        sql = (
            "INSERT INTO loans(loan_id,lender_person_id,borrower_person_id,principal_minor,currency_code,state,"
            "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"
        )
        values = (row_id, creditor, debtor, 100, currency, "proposed", STAMP, STAMP)
    elif table == "obligations":
        sql = (
            "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
            "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"
        )
        values = (row_id, creditor, debtor, 100, currency, "proposed", STAMP, STAMP)
    else:
        sql = (
            "INSERT INTO payments(payment_id,payer_person_id,payee_person_id,amount_minor,"
            "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"
        )
        values = (row_id, debtor, creditor, 100, currency, "initiated", STAMP, STAMP)

    with pytest.raises(sqlite3.IntegrityError, match="currency_code"):
        db.execute(sql, values)
    assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0
    db.close()


def test_financial_source_tables_accept_canonical_currency_codes():
    db = connect_database()
    creditor = _person(db)
    debtor = _person(db)
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), creditor, debtor, 100, "YER", "proposed", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO payments(payment_id,payer_person_id,payee_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), debtor, creditor, 100, "USD", "initiated", STAMP, STAMP),
    )
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 1
    db.close()


def test_currency_code_update_guards_reject_noncanonical_values():
    db = connect_database()
    creditor = _person(db)
    debtor = _person(db)
    obligation_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (obligation_id, creditor, debtor, 100, "YER", "proposed", STAMP, STAMP),
    )
    with pytest.raises(sqlite3.IntegrityError, match="currency_code"):
        db.execute("UPDATE obligations SET currency_code='yer' WHERE obligation_id=?", (obligation_id,))
    assert db.execute(
        "SELECT currency_code FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == "YER"
    db.close()


def test_financial_transaction_and_ledger_currency_guards():
    db = connect_database()
    owner = _person(db)
    account_id = str(uuid.uuid4())
    now = STAMP
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (account_id, owner, "personal", now, now),
    )
    with pytest.raises(sqlite3.IntegrityError, match="currency_code"):
        db.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
            "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
            "created_at,updated_at) VALUES(?,NULL,?,100,'yer','recognized','opening_balance',?,?,?,?)",
            (str(uuid.uuid4()), account_id, "opening-balance:invalid", now, now, now),
        )
    tx_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
        "created_at,updated_at) VALUES(?,NULL,?,100,'YER','recognized','opening_balance',?,?,?,?)",
        (tx_id, account_id, "opening-balance:valid", now, now, now),
    )
    with pytest.raises(sqlite3.IntegrityError, match="currency_code"):
        db.execute(
            "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
            "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
            (str(uuid.uuid4()), account_id, tx_id, 100, "yer", now, now, now),
        )
    db.close()
