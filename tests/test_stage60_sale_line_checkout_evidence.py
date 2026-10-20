"""Stage 60 regression tests for sale-line evidence integrity."""
import sqlite3

import pytest

from agent_core.application import SaleApplication
from agent_core.commerce_checkout import CommercialCheckoutApplication
from test_commercial_checkout import NOW, setup_checkout, uid


def test_checkout_persists_product_name_and_price_snapshot(tmp_path):
    db, request = setup_checkout(tmp_path)
    result = CommercialCheckoutApplication().checkout(db, **request, now=NOW)

    row = db.execute(
        """SELECT offering_id, product_id, quantity, unit_price_minor,
                  currency_code, line_total_minor, product_name_snapshot
           FROM sale_lines WHERE sale_id=?""",
        (request["sale_id"],),
    ).fetchone()
    product_id = db.execute(
        "SELECT product_id FROM offerings WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]

    assert row == (request["offering_id"], product_id, 2, 1250, "SAR", 2500, "Bottle")
    assert result["product_name"] == row[-1]
    assert result["unit_price_minor"] * result["quantity"] == result["total_minor"]


def test_database_rejects_sale_line_without_product_name_snapshot(tmp_path):
    db, request = setup_checkout(tmp_path)
    CommercialCheckoutApplication().checkout(db, **request, now=NOW)
    product_id = db.execute(
        "SELECT product_id FROM offerings WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]

    with pytest.raises(sqlite3.IntegrityError, match="product name snapshot"):
        db.execute(
            """INSERT INTO sale_lines(
                   sale_line_id, sale_id, offering_id, product_id, quantity,
                   unit_price_minor, currency_code, line_total_minor, created_at,
                   product_name_snapshot
               ) VALUES(?,?,?,?,1,1250,'SAR',1250,?,'   ')""",
            (uid(), request["sale_id"], request["offering_id"], product_id, "2026-10-17T12:01:00Z"),
        )
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM sale_lines WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 1


def test_database_rejects_sale_line_before_sale_is_fulfilled(tmp_path):
    db, request = setup_checkout(tmp_path)
    SaleApplication().create_sale(
        db, request["offering_id"], request["activity_id"],
        request["actor_person_id"], request["create_sale_grant_id"],
        now=NOW, sale_id=request["sale_id"],
    )
    product_id = db.execute(
        "SELECT product_id FROM offerings WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]

    with pytest.raises(sqlite3.IntegrityError, match="fulfilled Sale"):
        db.execute(
            """INSERT INTO sale_lines(
                   sale_line_id, sale_id, offering_id, product_id, quantity,
                   unit_price_minor, currency_code, line_total_minor, created_at,
                   product_name_snapshot
               ) VALUES(?,?,?,?,1,1250,'SAR',1250,?,'Bottle')""",
            (uid(), request["sale_id"], request["offering_id"], product_id, "2026-10-17T12:01:00Z"),
        )
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM sale_lines WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 0



def test_database_rejects_product_name_snapshot_that_disagrees_with_product(tmp_path):
    db, request = setup_checkout(tmp_path)
    CommercialCheckoutApplication().checkout(db, **request, now=NOW)
    product_id = db.execute(
        "SELECT product_id FROM offerings WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]

    with pytest.raises(sqlite3.IntegrityError, match="must match Product at checkout"):
        db.execute(
            """INSERT INTO sale_lines(
                   sale_line_id, sale_id, offering_id, product_id, quantity,
                   unit_price_minor, currency_code, line_total_minor, created_at,
                   product_name_snapshot
               ) VALUES(?,?,?,?,1,1250,'SAR',1250,?,'Forged product name')""",
            (uid(), request["sale_id"], request["offering_id"], product_id, "2026-10-17T12:01:00Z"),
        )
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM sale_lines WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 1
