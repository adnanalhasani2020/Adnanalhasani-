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
            "INSERT INTO loans(loan_id,lender_person_id,borrower_person_id,currency_code,state,"
            "created_at,updated_at) VALUES(?,?,?,?,?,?,?)"
        )
        values = (row_id, creditor, debtor, currency, "proposed", STAMP, STAMP)
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
