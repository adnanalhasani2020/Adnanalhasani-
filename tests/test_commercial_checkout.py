"""Commercial checkout acceptance tests: persisted prices, stock movements, and replay."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.inventory_read import InventoryQuantityStatus
from agent_core.persistence import connect_database
from agent_core.persisted_settlement_recognition_application import PersistedSettlementRecognitionApplication
from agent_core.shared import ValidationError
from agent_core.application import ProductApplication, SaleApplication
from agent_core.offering_application import OfferingApplication
from agent_core.offering_price_application import OfferingPriceApplication

NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-17T12:00:00Z"
BEFORE = "2026-10-17T11:00:00Z"


def uid():
    return str(uuid.uuid4())


def setup_checkout(tmp_path, quantity=5):
    db = connect_database(tmp_path / "checkout.sqlite")
    actor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor, "active", BEFORE, BEFORE),
    )
    activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?, 'active',?,?)",
        (activity, actor, BEFORE, BEFORE),
    )
    products = ProductApplication()
    product = products.create_product(db, "Bottle", actor_context_ref="checkout-test", now=BEFORE)
    products.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="checkout-test", now=BEFORE,
    )
    offerings = OfferingApplication()
    offering = offerings.create_offering(
        db, product_id=product.product.id, activity_id=activity,
        effective_from=BEFORE, actor_context_ref="checkout-test", now=BEFORE,
    )
    offerings.transition_offering(
        db, offering.offering.id, "activate", expected_version=1,
        actor_context_ref="checkout-test", now=BEFORE,
    )
    offering_id = str(offering.offering.id)
    product_id = str(product.product.id)
    position_id = uid()
    db.execute(
        """INSERT INTO inventory_positions(
               inventory_position_id,activity_id,product_id,offering_id,location_ref,
               scope_key,state,quantity_minor,observed_at,effective_from,effective_to,
               created_at,updated_at,provenance_id,version_no
           ) VALUES(?,?,?,?,?,'store-A','effective',?,?,?,NULL,?,?,NULL,1)""",
        (position_id, activity, product_id, offering_id, "front", quantity,
         BEFORE, BEFORE, BEFORE, BEFORE),
    )
    db.execute(
        """INSERT INTO offering_prices(
               offering_price_id,offering_id,unit_price_minor,currency_code,
               effective_from,effective_to,actor_context_ref,created_at
           ) VALUES(?,?,1250,'SAR',?,NULL,'checkout-test',?)""",
        (uid(), offering_id, BEFORE, BEFORE),
    )
    sale_id = uid()

    def grant(action, scope):
        grant_id = uid()
        db.execute(
            """INSERT INTO authorization_grants(
                   authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
                   context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
               ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,NULL,?,?)""",
            (grant_id, actor, action, scope, activity, "2026-01-01T00:00:00Z", BEFORE, BEFORE),
        )
        return grant_id

    grants = {
        "create": grant("create_sale", offering_id),
        "confirm": grant("confirm_sale", sale_id),
        "fulfill": grant("fulfill_sale", sale_id),
    }
    db.commit()
    return db, {
        "actor_person_id": actor, "activity_id": activity, "offering_id": offering_id,
        "quantity": 2, "scope_key": "store-A", "location_ref": "front",
        "idempotency_key": "checkout-001", "sale_id": sale_id,
        "create_sale_grant_id": grants["create"],
        "confirm_sale_grant_id": grants["confirm"],
        "fulfill_sale_grant_id": grants["fulfill"],
    }


def test_checkout_fulfills_sale_and_records_stock_movement_atomically(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    result = app.checkout(db, **request, now=NOW)

    assert result["state"] == "fulfilled"
    assert result["quantity"] == 2
    assert result["unit_price_minor"] == 1250
    assert result["currency_code"] == "SAR"
    assert result["total_minor"] == 2500
    assert result["quantity_remaining"] == 3
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT quantity_delta FROM inventory_movements").fetchone()[0] == -2
    assert db.execute("SELECT state FROM sales WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] in {"completed", "fulfilled"}

    listed = app.list_sellable_offerings(
        db, activity_id=request["activity_id"], scope_key="store-A",
        location_ref="front", as_of=datetime(2026, 10, 17, 12, 1, tzinfo=timezone.utc),
    )
    assert len(listed) == 1
    assert listed[0]["available_quantity"] == 3


def test_returning_checkout_sale_restores_stock_atomically_once(tmp_path):
    db, request = setup_checkout(tmp_path)
    checkout = CommercialCheckoutApplication()
    checkout.checkout(db, **request, now=NOW)

    grant_id = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'return_sale',?,?,NULL,'active',?,NULL,?,?)""",
        (grant_id, request["actor_person_id"], request["sale_id"], request["activity_id"],
         BEFORE, BEFORE, BEFORE),
    )
    db.commit()

    SaleApplication().return_sale(
        db, request["sale_id"], request["actor_person_id"], grant_id,
        now=datetime(2026, 10, 17, 12, 2, tzinfo=timezone.utc),
    )

    movements = db.execute(
        "SELECT source_type, quantity_delta FROM inventory_movements "
        "WHERE (source_type='sale' AND source_ref=?) OR "
        "(source_type='sale_return' AND source_ref IN ("
        "SELECT rl.sale_return_line_id FROM sale_return_lines rl "
        "JOIN sale_return_events re ON re.return_event_id=rl.return_event_id WHERE re.sale_id=?)) "
        "ORDER BY created_at, inventory_movement_id",
        (request["sale_id"], request["sale_id"]),
    ).fetchall()
    assert sorted(movements) == [("sale", -2), ("sale_return", 2)]

    # A repeated movement for the same return line is rejected at the database boundary.
    with pytest.raises(sqlite3.IntegrityError, match="Sale stock has already been returned"):
        db.execute(
            "INSERT INTO inventory_movements("
            "inventory_movement_id,inventory_position_id,activity_id,product_id,offering_id,"
            "location_ref,scope_key,quantity_delta,source_type,source_ref,actor_context_ref,"
            "occurred_at,created_at) "
            "SELECT ?,inventory_position_id,activity_id,product_id,offering_id,location_ref,"
            "scope_key,quantity_delta,source_type,source_ref,actor_context_ref,occurred_at,created_at "
            "FROM inventory_movements WHERE source_type='sale_return' AND source_ref IN ("
            "SELECT rl.sale_return_line_id FROM sale_return_lines rl "
            "JOIN sale_return_events re ON re.return_event_id=rl.return_event_id WHERE re.sale_id=?)",
            (uid(), request["sale_id"]),
        )
    listed = checkout.list_sellable_offerings(
        db, activity_id=request["activity_id"], scope_key="store-A",
        location_ref="front",
        as_of=datetime(2026, 10, 17, 12, 3, tzinfo=timezone.utc),
    )
    assert listed[0]["available_quantity"] == 5

    retry_grant = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'return_sale',?,?,NULL,'active',?,NULL,?,?)""",
        (retry_grant, request["actor_person_id"], request["sale_id"], request["activity_id"],
         BEFORE, BEFORE, BEFORE),
    )
    db.commit()
    with pytest.raises(ValidationError, match="Invalid Sale transition"):
        SaleApplication().return_sale(
            db, request["sale_id"], request["actor_person_id"], retry_grant,
            now=datetime(2026, 10, 17, 12, 4, tzinfo=timezone.utc),
        )
    assert db.execute(
        "SELECT COUNT(*) FROM inventory_movements WHERE source_type='sale_return' AND source_ref IN ("
        "SELECT rl.sale_return_line_id FROM sale_return_lines rl "
        "JOIN sale_return_events re ON re.return_event_id=rl.return_event_id WHERE re.sale_id=?)",
        (request["sale_id"],),
    ).fetchone()[0] == 1





def test_checkout_can_issue_an_authorized_invoice_atomically(tmp_path):
    db, request = setup_checkout(tmp_path)
    invoice_grant = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'issue_invoice',?,?,NULL,'active',?,NULL,?,?)""",
        (invoice_grant, request["actor_person_id"], request["sale_id"], request["activity_id"],
         BEFORE, BEFORE, BEFORE),
    )
    db.commit()
    result = CommercialCheckoutApplication().checkout(
        db, **request, invoice_issuer_ref=request["actor_person_id"],
        invoice_number="INV-CHECKOUT-001", issue_invoice_grant_id=invoice_grant, now=NOW,
    )
    assert result["invoice_number"] == "INV-CHECKOUT-001"
    assert result["invoice_state"] == "issued"
    assert db.execute(
        "SELECT sale_id,state,issuer_ref,invoice_number FROM invoices WHERE invoice_id=?",
        (result["invoice_id"],),
    ).fetchone() == (
        request["sale_id"], "issued", request["actor_person_id"], "INV-CHECKOUT-001",
    )
    receipt = db.execute(
        "SELECT response_json FROM commercial_checkout_receipts WHERE idempotency_key=?",
        (request["idempotency_key"],),
    ).fetchone()[0]
    assert result["invoice_id"] in receipt
    replay = CommercialCheckoutApplication().checkout(
        db, **request, invoice_issuer_ref=request["actor_person_id"],
        invoice_number="INV-CHECKOUT-001", issue_invoice_grant_id=invoice_grant, now=NOW,
    )
    assert replay == result
    assert db.execute("SELECT COUNT(*) FROM invoices WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 1
    db.close()


def test_invoice_issuance_failure_rolls_back_checkout_sale_stock_and_receipt(tmp_path):
    db, request = setup_checkout(tmp_path)
    wrong_grant = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'confirm_sale',?,?,NULL,'active',?,NULL,?,?)""",
        (wrong_grant, request["actor_person_id"], request["sale_id"], request["activity_id"],
         BEFORE, BEFORE, BEFORE),
    )
    db.commit()
    with pytest.raises(ValidationError, match="does not permit action issue_invoice"):
        CommercialCheckoutApplication().checkout(
            db, **request, invoice_issuer_ref=request["actor_person_id"],
            invoice_number="INV-CHECKOUT-FAIL", issue_invoice_grant_id=wrong_grant, now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM sales WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements WHERE source_ref=?", (request["sale_id"],)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM invoices WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts WHERE idempotency_key=?", (request["idempotency_key"],)).fetchone()[0] == 0
    db.close()


def test_commerce_sale_payment_settlement_and_finance_recognition_are_one_governed_chain(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=2)
    sale_id = request["sale_id"]
    debtor = request["actor_person_id"]
    creditor, approver, agent, context = [uid() for _ in range(4)]
    account, obligation, payment, settlement = [uid() for _ in range(4)]
    for person in (creditor, approver):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, BEFORE, BEFORE),
        )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?, 'cash','active',?,?)",
        (account, creditor, BEFORE, BEFORE),
    )
    price = db.execute(
        "SELECT unit_price_minor,currency_code FROM offering_prices WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()
    amount = price[0] * request["quantity"]
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at,version_no) VALUES(?,?,?,?,?,'open',?,?,1)",
        (obligation, creditor, debtor, amount, price[1], BEFORE, BEFORE),
    )
    invoice_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,"
        "context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,NULL,'issue_invoice',?,?,NULL,'active',?,NULL,?,?)",
        (invoice_grant, debtor, sale_id, request["activity_id"], BEFORE, BEFORE, BEFORE),
    )
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?, 'active',?,?)",
        (agent, approver, "commerce-finance-agent", context, BEFORE, BEFORE),
    )
    db.commit()

    checkout_result = CommercialCheckoutApplication().checkout(
        db, **request, invoice_issuer_ref=creditor,
        invoice_number="INV-CYCLE-001", issue_invoice_grant_id=invoice_grant,
        invoice_obligation_id=obligation, now=NOW,
    )
    invoice = checkout_result["invoice_id"]
    assert checkout_result["invoice_state"] == "issued"
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,?,?,?,?,'completed',?,?,1)",
        (payment, obligation, invoice, debtor, creditor, amount, price[1], STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (uid(), agent, PersistedSettlementRecognitionApplication.ACTION_CODE,
         settlement, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    grant = db.execute(
        "SELECT authorization_grant_id FROM authorization_grants WHERE scope_ref=? AND action_code=?",
        (settlement, PersistedSettlementRecognitionApplication.ACTION_CODE),
    ).fetchone()[0]
    action = uid()
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'approved',?,?)",
        (action, agent, PersistedSettlementRecognitionApplication.ACTION_CODE,
         settlement, context, grant, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
        "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
        (uid(), action, approver, STAMP, STAMP, STAMP),
    )
    db.commit()

    result = PersistedSettlementRecognitionApplication().settle_and_recognize_commerce_sale(
        db, sale_id=sale_id, payment_id=payment, obligation_id=obligation,
        settlement_id=settlement, financial_account_id=account, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert result.replayed is False
    assert result.amount_minor == amount
    assert db.execute(
        "SELECT payment_id,obligation_id,state,settled_at FROM settlements WHERE settlement_id=?",
        (settlement,),
    ).fetchone() == (payment, obligation, "settled", STAMP)
    assert db.execute(
        "SELECT sale_id,obligation_id,state FROM invoices WHERE invoice_id=?", (invoice,)
    ).fetchone() == (sale_id, obligation, "issued")
    assert db.execute(
        "SELECT state FROM obligations WHERE obligation_id=?", (obligation,)
    ).fetchone()[0] == "satisfied"
    assert db.execute(
        "SELECT COUNT(*) FROM financial_transactions WHERE settlement_id=? AND state='recognized'",
        (settlement,),
    ).fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM ledger_entries WHERE financial_transaction_id=? AND state='posted'",
        (str(result.financial_transaction_id),),
    ).fetchone()[0] == 1
    replay = PersistedSettlementRecognitionApplication().settle_and_recognize_commerce_sale(
        db, sale_id=sale_id, payment_id=payment, obligation_id=obligation,
        settlement_id=settlement, financial_account_id=account, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert replay.replayed is True
    assert db.execute("SELECT COUNT(*) FROM settlements WHERE obligation_id=?", (obligation,)).fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM financial_transactions WHERE settlement_id=?", (settlement,)).fetchone()[0] == 1
    db.close()


def test_partial_and_multiple_returns_are_idempotent_and_quantity_bounded(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=2)
    CommercialCheckoutApplication().checkout(db, **request, now=NOW)
    sale_line_id = db.execute(
        "SELECT sale_line_id FROM sale_lines WHERE sale_id=?", (request["sale_id"],)
    ).fetchone()[0]
    grant_id = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'return_sale',?,?,NULL,'active',?,NULL,?,?)""",
        (grant_id, request["actor_person_id"], request["sale_id"], request["activity_id"],
         BEFORE, BEFORE, BEFORE),
    )
    db.commit()
    app = SaleApplication()
    first = app.return_sale_items(
        db, request["sale_id"], request["actor_person_id"], grant_id,
        items=[{"sale_line_id": sale_line_id, "quantity": 1}],
        idempotency_key="return-partial-001", reason="customer returned one unit",
        now=datetime(2026, 10, 17, 12, 2, tzinfo=timezone.utc),
    )
    assert first["replayed"] is False
    assert first["sale_state"] == "fulfilled"
    assert db.execute(
        "SELECT COUNT(*) FROM inventory_movements WHERE source_type='sale_return'"
    ).fetchone()[0] == 1

    replay = app.return_sale_items(
        db, request["sale_id"], request["actor_person_id"], grant_id,
        items=[{"sale_line_id": sale_line_id, "quantity": 1}],
        idempotency_key="return-partial-001", reason="customer returned one unit",
        now=datetime(2026, 10, 17, 12, 3, tzinfo=timezone.utc),
    )
    assert replay["return_id"] == first["return_id"]
    assert replay["replayed"] is True
    with pytest.raises(ValidationError, match="idempotency key was reused with a different request"):
        app.return_sale_items(
            db, request["sale_id"], request["actor_person_id"], grant_id,
            items=[{"sale_line_id": sale_line_id, "quantity": 1}],
            idempotency_key="return-partial-001", reason="changed reason for same key",
            now=datetime(2026, 10, 17, 12, 3, 30, tzinfo=timezone.utc),
        )
    with pytest.raises(ValidationError, match="Cumulative returned quantity exceeds"):
        app.return_sale_items(
            db, request["sale_id"], request["actor_person_id"], grant_id,
            items=[{"sale_line_id": sale_line_id, "quantity": 2}],
            idempotency_key="return-overflow-001", reason="attempt over return",
            now=datetime(2026, 10, 17, 12, 4, tzinfo=timezone.utc),
        )
    second = app.return_sale_items(
        db, request["sale_id"], request["actor_person_id"], grant_id,
        items=[{"sale_line_id": sale_line_id, "quantity": 1}],
        idempotency_key="return-partial-002", reason="customer returned remaining unit",
        now=datetime(2026, 10, 17, 12, 5, tzinfo=timezone.utc),
    )
    assert second["replayed"] is False
    assert second["sale_state"] == "returned"
    assert SaleApplication().get_sale_state(db, request["sale_id"]).value == "returned"
    assert db.execute(
        "SELECT COUNT(*) FROM sale_return_events WHERE sale_id=?", (request["sale_id"],)
    ).fetchone()[0] == 2
    assert db.execute(
        "SELECT SUM(quantity) FROM sale_return_lines rl JOIN sale_return_events re "
        "ON re.return_event_id=rl.return_event_id WHERE re.sale_id=?", (request["sale_id"],)
    ).fetchone()[0] == 2
    assert db.execute(
        "SELECT SUM(quantity_delta) FROM inventory_movements WHERE source_type='sale_return'"
    ).fetchone()[0] == 2


def test_database_rejects_stock_return_while_invoice_is_issued(tmp_path):
    db, request = setup_checkout(tmp_path)
    CommercialCheckoutApplication().checkout(db, **request, now=NOW)
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,"
        "issue_at,due_at,created_at,updated_at,provenance_id,version_no) "
        "VALUES(?,?,NULL,'issued','issuer-test','INV-RETURN-GUARD',?,NULL,?,?,NULL,1)",
        (uid(), request["sale_id"], STAMP, STAMP, STAMP),
    )
    db.commit()

    line_id = db.execute(
        "SELECT sale_line_id,quantity FROM sale_lines WHERE sale_id=?",
        (request["sale_id"],),
    ).fetchone()
    event_id = uid()
    return_line_id = uid()
    db.execute(
        "INSERT INTO sale_return_events(return_event_id,sale_id,idempotency_key,request_fingerprint,reason,actor_context_ref,created_at) "
        "VALUES(?,?,?,'direct-sql-test','test return',?,?)",
        (event_id, request["sale_id"], "direct-sql-return-001", request["actor_person_id"], STAMP),
    )
    db.execute(
        "INSERT INTO sale_return_lines(sale_return_line_id,return_event_id,sale_line_id,quantity,created_at) "
        "VALUES(?,?,?,?,?)",
        (return_line_id, event_id, line_id[0], line_id[1], STAMP),
    )
    with pytest.raises(
        sqlite3.IntegrityError,
        match="Sale return requires explicit Invoice reconciliation first",
    ):
        db.execute(
            "INSERT INTO inventory_movements("
            "inventory_movement_id,inventory_position_id,activity_id,product_id,offering_id,"
            "location_ref,scope_key,quantity_delta,source_type,source_ref,actor_context_ref,"
            "occurred_at,created_at) "
            "SELECT ?,inventory_position_id,activity_id,product_id,offering_id,location_ref,"
            "scope_key,?,'sale_return',?,?,?,? "
            "FROM inventory_movements WHERE source_type='sale' AND source_ref=?",
            (uid(), line_id[1], return_line_id, request["actor_person_id"], STAMP, STAMP, request["sale_id"]),
        )

    assert db.execute(
        "SELECT state FROM sales WHERE sale_id=?", (request["sale_id"],)
    ).fetchone()[0] in {"completed", "fulfilled"}
    assert db.execute(
        "SELECT COUNT(*) FROM inventory_movements WHERE source_type='sale_return' AND source_ref=?",
        (request["sale_id"],),
    ).fetchone()[0] == 0


def test_checkout_replay_returns_same_result_without_second_deduction(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    first = app.checkout(db, **request, now=NOW)
    replay = app.checkout(
        db, **request,
        now=datetime(2026, 10, 17, 12, 5, tzinfo=timezone.utc),
    )

    assert replay == first
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1


def test_checkout_rejects_quantity_above_available_without_partial_writes(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=1)
    request["quantity"] = 2
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="exceeds available stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 0


def test_checkout_rejects_reused_key_with_changed_payload(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    app.checkout(db, **request, now=NOW)
    changed = dict(request, quantity=1)

    with pytest.raises(ValidationError, match="different request"):
        app.checkout(db, **changed, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1


def test_checkout_rejects_unknown_inventory_without_sale(tmp_path):
    db, request = setup_checkout(tmp_path)
    db.execute("UPDATE inventory_positions SET quantity_minor=NULL")
    db.commit()
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="known, effective, unambiguous stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0



def test_checkout_rolls_back_sale_and_history_if_stock_movement_write_fails(tmp_path):
    db, request = setup_checkout(tmp_path)
    db.execute(
        """CREATE TRIGGER force_movement_failure
           BEFORE INSERT ON inventory_movements
           BEGIN SELECT RAISE(ABORT, 'forced movement failure'); END"""
    )
    app = CommercialCheckoutApplication()

    with pytest.raises(Exception, match="forced movement failure"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (request["sale_id"],)
    ).fetchone()[0] == 0


def test_checkout_rejects_known_zero_stock(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=0)
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="exceeds available stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0



def test_price_change_closes_open_window_and_records_actor_history(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = OfferingPriceApplication()

    result = app.set_price(
        db, offering_id=request["offering_id"], unit_price_minor=1500,
        currency_code="SAR", actor_context_ref=request["actor_person_id"],
        effective_from=NOW, now=NOW,
    )

    rows = db.execute(
        """SELECT unit_price_minor,effective_from,effective_to,actor_context_ref
           FROM offering_prices WHERE offering_id=? ORDER BY effective_from""",
        (request["offering_id"],),
    ).fetchall()
    assert len(rows) == 2
    assert rows[0][0] == 1250 and rows[0][2] == NOW.isoformat(timespec="microseconds").replace("+00:00", "Z")
    assert rows[1][0] == 1500 and rows[1][2] is None
    assert rows[1][3] == request["actor_person_id"]
    assert result["unit_price_minor"] == 1500
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type IN ('offering_price_set','offering_price_window_closed')"
    ).fetchone()[0] == 2



def test_concurrent_checkouts_cannot_sell_the_last_unit_twice(tmp_path):
    import threading
    from datetime import timedelta

    db, base_request = setup_checkout(tmp_path, quantity=1)
    base_request["quantity"] = 1
    database_path = tmp_path / "checkout.sqlite"
    db.close()
    barrier = threading.Barrier(2)
    outcomes = []
    outcome_lock = threading.Lock()

    def worker(index):
        connection = connect_database(database_path)
        request = dict(base_request, idempotency_key=f"checkout-concurrent-{index}", sale_id=uid())
        grants = []
        for action, scope in (
            ("create_sale", request["offering_id"]),
            ("confirm_sale", request["sale_id"]),
            ("fulfill_sale", request["sale_id"]),
        ):
            grant_id = uid()
            connection.execute(
                """INSERT INTO authorization_grants(
                       authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
                       context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
                   ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,NULL,?,?)""",
                (grant_id, request["actor_person_id"], action, scope, request["activity_id"],
                 "2026-01-01T00:00:00Z", BEFORE, BEFORE),
            )
            grants.append(grant_id)
        request["create_sale_grant_id"], request["confirm_sale_grant_id"], request["fulfill_sale_grant_id"] = grants
        connection.commit()
        barrier.wait()
        try:
            CommercialCheckoutApplication().checkout(
                connection, **request, now=NOW + timedelta(seconds=index + 1)
            )
            outcome = ("success", "")
        except Exception as exc:
            outcome = ("rejected", f"{type(exc).__name__}: {exc}")
        finally:
            connection.close()
        with outcome_lock:
            outcomes.append(outcome)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)

    assert all(not thread.is_alive() for thread in threads), "checkout concurrency test timed out"
    assert sorted(item[0] for item in outcomes) == ["rejected", "success"], repr(outcomes)
    verify = connect_database(database_path)
    assert verify.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert verify.execute("SELECT SUM(quantity_delta) FROM inventory_movements").fetchone()[0] == -1
    assert verify.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    verify.close()

def test_checkout_uses_current_effective_price_not_stale_catalog_price(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    prices = OfferingPriceApplication()
    changed_at = NOW + timedelta(minutes=1)
    prices.set_price(
        db,
        offering_id=request["offering_id"],
        unit_price_minor=1500,
        currency_code="SAR",
        actor_context_ref=request["actor_person_id"],
        effective_from=changed_at,
        now=changed_at,
    )

    # The caller may have rendered the earlier 1,250-minor-unit catalog price;
    # checkout must price from the effective server-side record at commit time.
    result = app.checkout(db, **request, now=NOW + timedelta(minutes=2))

    assert result["unit_price_minor"] == 1500
    assert result["total_minor"] == 3000
    assert result["quantity"] == 2
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1

def test_checkout_idempotency_survives_database_reopen(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    first = app.checkout(db, **request, now=NOW)
    db.close()

    # Simulate a process restart after commit but before the client receives
    # the response. A retry must recover the original receipt, not sell again.
    reopened = connect_database(tmp_path / "checkout.sqlite")
    replay = app.checkout(
        reopened,
        **request,
        now=NOW + timedelta(minutes=10),
    )

    assert replay == first
    assert reopened.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    assert reopened.execute("SELECT SUM(quantity_delta) FROM inventory_movements").fetchone()[0] == -2
    reopened.close()


def test_checkout_receipt_does_not_imply_financial_recognition_or_ledger_posting(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    # A commercial sale/receipt is not, by itself, evidence that money moved
    # or that an approved accounting-recognition event occurred.
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0

    result = app.checkout(db, **request, now=NOW)

    assert result["state"] == "fulfilled"
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0



@pytest.mark.parametrize(
    ("occurred_at", "accepted"),
    [
        ("2026-10-17T10:59:59Z", False),  # before effective_from
        ("2026-10-17T11:00:00Z", True),   # inclusive effective_from
        ("2026-10-17T13:00:00+02:00", True),  # same instant as start, different offset
        ("2026-10-17T13:15:00+02:00", True),  # same instant basis, offset format
        ("2026-10-17T13:29:59.999999+02:00", True),  # 1 microsecond before end
        ("2026-10-17T11:30:00Z", False),  # exclusive effective_to
        ("2026-10-17T13:30:00+02:00", False),  # same instant as exclusive end
        ("2026-10-17T11:00:00.0000001Z", False),  # reject precision below one microsecond
    ],
)
def test_inventory_movement_must_fall_inside_position_effective_window(tmp_path, occurred_at, accepted):
    db, request = setup_checkout(tmp_path)
    sale_app = SaleApplication()
    db.execute(
        "UPDATE inventory_positions SET effective_to=? WHERE inventory_position_id=("
        "SELECT inventory_position_id FROM inventory_positions WHERE offering_id=?)",
        ("2026-10-17T11:30:00Z", request["offering_id"]),
    )
    db.commit()

    sale_app.create_sale(
        db, request["offering_id"], request["activity_id"], request["actor_person_id"],
        request["create_sale_grant_id"], now=NOW, sale_id=request["sale_id"],
    )
    sale_app.confirm_sale(
        db, request["sale_id"], request["actor_person_id"],
        request["confirm_sale_grant_id"], now=NOW,
    )
    sale_app.fulfill_sale(
        db, request["sale_id"], request["actor_person_id"],
        request["fulfill_sale_grant_id"], now=NOW,
    )
    position_id = db.execute(
        "SELECT inventory_position_id FROM inventory_positions WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]
    db.execute(
        """INSERT INTO sale_lines(
               sale_line_id, sale_id, offering_id, product_id, quantity,
               unit_price_minor, currency_code, line_total_minor, created_at,
               product_name_snapshot
           ) VALUES(?,?,?,?,1,1250,'SAR',1250,?,'Bottle')""",
        (uid(), request["sale_id"], request["offering_id"],
         db.execute("SELECT product_id FROM offerings WHERE offering_id=?",
                    (request["offering_id"],)).fetchone()[0], STAMP),
    )
    db.commit()

    # Reopen the persisted database so this also checks that the migration-installed
    # trigger remains effective after a connection/process restart.
    db.close()
    db = connect_database(tmp_path / "checkout.sqlite")

    insert_movement = """INSERT INTO inventory_movements(
                   inventory_movement_id, inventory_position_id, activity_id, product_id,
                   offering_id, location_ref, scope_key, quantity_delta, source_type,
                   source_ref, actor_context_ref, occurred_at, created_at
               ) VALUES(?,?,?,?,?,?,?,-1,'sale',?,?,?,?)"""
    movement_values = (
        uid(), position_id, request["activity_id"],
        db.execute("SELECT product_id FROM offerings WHERE offering_id=?",
                   (request["offering_id"],)).fetchone()[0],
        request["offering_id"], request["location_ref"], request["scope_key"],
        request["sale_id"], request["actor_person_id"], occurred_at, STAMP,
    )
    if accepted:
        db.execute(insert_movement, movement_values)
        db.commit()
    else:
        with pytest.raises(sqlite3.IntegrityError, match="within its position effective window|at most 6 fractional digits"):
            db.execute(insert_movement, movement_values)
        db.rollback()
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == int(accepted)
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT state FROM sales WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] in {"completed", "fulfilled"}
    db.close()



def test_checkout_rejects_total_above_sqlite_integer_range_without_partial_writes(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path, quantity=5)
    prices = OfferingPriceApplication()
    prices.set_price(
        db,
        offering_id=request["offering_id"],
        unit_price_minor=(1 << 62),
        currency_code="SAR",
        actor_context_ref=request["actor_person_id"],
        effective_from=NOW,
        now=NOW,
    )

    with pytest.raises(ValidationError, match="Sale total exceeds the supported integer range"):
        CommercialCheckoutApplication().checkout(
            db, **request, now=NOW + timedelta(minutes=1)
        )

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 0



def test_offering_price_rejects_value_outside_sqlite_integer_range(tmp_path):
    db, request = setup_checkout(tmp_path)
    with pytest.raises(ValidationError, match="signed 64-bit integer"):
        OfferingPriceApplication().set_price(
            db,
            offering_id=request["offering_id"],
            unit_price_minor=(1 << 63),
            currency_code="SAR",
            actor_context_ref=request["actor_person_id"],
            effective_from=NOW,
            now=NOW,
        )

    assert db.execute("SELECT COUNT(*) FROM offering_prices WHERE unit_price_minor > 0").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE change_type='offering_price_set'").fetchone()[0] == 0


def test_return_fails_closed_when_checkout_sale_line_is_missing(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    app.checkout(db, **request, now=NOW)

    return_grant_id = uid()
    db.execute(
        """INSERT INTO authorization_grants(
               authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
               context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
           ) VALUES(?,?,NULL,'return_sale',?,?,NULL,'active',?,NULL,?,?)""",
        (return_grant_id, request["actor_person_id"], request["sale_id"],
         request["activity_id"], "2026-01-01T00:00:00Z", STAMP, STAMP),
    )
    # Simulate corruption by bypassing the production append-only guard only in this test.
    db.execute("DROP TRIGGER sale_line_immutable_delete")
    db.execute("DELETE FROM sale_lines WHERE sale_id=?", (request["sale_id"],))
    db.commit()
    state_before = db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (request["sale_id"],)
    ).fetchone()

    with pytest.raises(ValidationError, match="one reconciled sale line"):
        SaleApplication().return_sale(
            db, request["sale_id"], request["actor_person_id"], return_grant_id,
            now=NOW + timedelta(minutes=1),
        )

    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (request["sale_id"],)
    ).fetchone() == state_before
    assert db.execute(
        "SELECT COUNT(*) FROM inventory_movements WHERE source_type='sale_return' AND source_ref=?",
        (request["sale_id"],),
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT SUM(quantity_delta) FROM inventory_movements WHERE source_ref=?",
        (request["sale_id"],),
    ).fetchone()[0] == -2



def test_checkout_fails_closed_when_stock_observation_ties_inventory_movement_time(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    app.checkout(db, **request, now=NOW)

    # Simulate a stock observation whose timestamp is identical to the committed
    # sale movement. The data cannot prove whether quantity_minor already includes
    # that movement, so a later checkout must not guess and risk overselling.
    db.execute(
        "UPDATE inventory_positions SET quantity_minor=5, observed_at=? WHERE offering_id=?",
        (STAMP, request["offering_id"]),
    )
    sale_id = uid()

    def grant(action, scope):
        grant_id = uid()
        db.execute(
            """INSERT INTO authorization_grants(
                   authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
                   context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
               ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,NULL,?,?)""",
            (grant_id, request["actor_person_id"], action, scope, request["activity_id"],
             "2026-01-01T00:00:00Z", STAMP, STAMP),
        )
        return grant_id

    retry = dict(
        request,
        idempotency_key="checkout-after-tied-observation",
        sale_id=sale_id,
        create_sale_grant_id=grant("create_sale", request["offering_id"]),
        confirm_sale_grant_id=grant("confirm_sale", sale_id),
        fulfill_sale_grant_id=grant("fulfill_sale", sale_id),
    )
    db.commit()

    with pytest.raises(ValidationError, match="known, effective, unambiguous stock"):
        app.checkout(db, **retry, now=NOW + timedelta(minutes=1))

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1



def test_checkout_receipt_is_immutable_and_remains_replayable(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    original = app.checkout(db, **request, now=NOW)

    with pytest.raises(sqlite3.IntegrityError, match="receipt evidence is immutable"):
        db.execute(
            "UPDATE commercial_checkout_receipts SET response_json='{}' "
            "WHERE actor_person_id=? AND idempotency_key=?",
            (request["actor_person_id"], request["idempotency_key"]),
        )
    db.rollback()

    with pytest.raises(sqlite3.IntegrityError, match="receipt evidence is immutable"):
        db.execute(
            "DELETE FROM commercial_checkout_receipts "
            "WHERE actor_person_id=? AND idempotency_key=?",
            (request["actor_person_id"], request["idempotency_key"]),
        )
    db.rollback()

    replay = app.checkout(db, **request, now=NOW)
    assert replay == original
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
