import sqlite3
from pathlib import Path
from uuid import uuid4

import pytest

from agent_core.domain_purchases import PurchaseLine, PurchaseState
from agent_core.purchase_application import PurchaseApplication
from agent_core.shared import ValidationError


ROOT = Path(__file__).resolve().parents[1]


def database():
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript((ROOT / "schema/migrations/0001_initial_relational_schema.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0007_product_name.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0008_purchases.sql").read_text())
    now = "2026-10-13T12:00:00Z"
    person, activity, product = str(uuid4()), str(uuid4()), str(uuid4())
    connection.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person, "active", now, now),
    )
    connection.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity, person, "active", now, now),
    )
    connection.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at,name) VALUES(?,?,?,?,?)",
        (product, "active", now, now, "Widget"),
    )
    return connection, person, activity, product


def grant(connection, person, action, scope, activity):
    grant_id = str(uuid4())
    now = "2026-10-13T00:00:00Z"
    connection.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,effective_to,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        (grant_id, person, action, scope, activity, "active", now, None, now, now),
    )
    return grant_id


def test_persisted_purchase_lifecycle_and_receipts_are_atomic():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    create_grant = grant(connection, person, "create_purchase", activity, activity)
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=5, unit_label="box")
    record = app.create_purchase(
        connection, "organization:supplier-1", activity, (line,), person, create_grant,
        idempotency_key="create-1",
    )
    purchase_id = str(record.purchase.id)

    for action, grant_action in [
        ("submit", "submit_purchase"),
        ("approve", "approve_purchase"),
        ("order", "order_purchase"),
    ]:
        auth = grant(connection, person, grant_action, purchase_id, activity)
        record = app.transition_purchase(connection, purchase_id, person, auth, action)

    auth = grant(connection, person, "receive_purchase", purchase_id, activity)
    record = app.receive_purchase(connection, purchase_id, str(line.id), 2, person, auth, idempotency_key="receipt-1")
    assert record.purchase.state is PurchaseState.PARTIALLY_RECEIVED
    assert record.version_no == 5

    auth = grant(connection, person, "receive_purchase", purchase_id, activity)
    record = app.receive_purchase(connection, purchase_id, str(line.id), 3, person, auth, idempotency_key="receipt-2")
    assert record.purchase.state is PurchaseState.RECEIVED
    assert record.version_no == 6

    restored = app.get_purchase(connection, purchase_id)
    assert restored.purchase == record.purchase
    assert sum(event.quantity_units for event in restored.purchase.receipts) == 5
    assert connection.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='procurement'").fetchone()[0] == 6
    # Receiving is evidence only; InventoryPosition remains untouched.
    assert connection.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0


def test_purchase_rejects_missing_or_wrong_authorization_without_partial_writes():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    with pytest.raises(ValidationError):
        app.create_purchase(connection, "supplier:1", activity, (line,), person, str(uuid4()), idempotency_key="create-failed")
    assert connection.execute("SELECT COUNT(*) FROM purchases").fetchone()[0] == 0
    assert connection.execute("SELECT COUNT(*) FROM purchase_lines").fetchone()[0] == 0


def test_purchase_receipt_overage_rolls_back_every_write():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    record = app.create_purchase(
        connection, "supplier:1", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity), idempotency_key="create-3",
    )
    purchase_id = str(record.purchase.id)
    for action, action_code in [("submit", "submit_purchase"), ("approve", "approve_purchase"), ("order", "order_purchase")]:
        record = app.transition_purchase(
            connection, purchase_id, person, grant(connection, person, action_code, purchase_id, activity), action,
        )
    before = connection.execute("SELECT version_no FROM purchases WHERE purchase_id=?", (purchase_id,)).fetchone()[0]
    with pytest.raises(ValidationError):
        app.receive_purchase(
            connection, purchase_id, str(line.id), 3, person,
            grant(connection, person, "receive_purchase", purchase_id, activity), idempotency_key="over-receipt",
        )
    after = connection.execute("SELECT version_no FROM purchases WHERE purchase_id=?", (purchase_id,)).fetchone()[0]
    assert after == before
    assert connection.execute("SELECT COUNT(*) FROM purchase_receipts WHERE purchase_id=?", (purchase_id,)).fetchone()[0] == 0



def test_create_and_receipt_retries_do_not_duplicate_records():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=3, unit_label="piece")
    create_grant = grant(connection, person, "create_purchase", activity, activity)
    first = app.create_purchase(
        connection, "supplier:1", activity, (line,), person, create_grant,
        idempotency_key="create-retry",
    )
    second = app.create_purchase(
        connection, "supplier:1", activity, (line,), person, create_grant,
        idempotency_key="create-retry",
    )
    assert second.purchase.id == first.purchase.id
    assert connection.execute("SELECT COUNT(*) FROM purchases").fetchone()[0] == 1
    assert connection.execute("SELECT COUNT(*) FROM purchase_lines").fetchone()[0] == 1

    purchase_id = str(first.purchase.id)
    for action, action_code in [("submit", "submit_purchase"), ("approve", "approve_purchase"), ("order", "order_purchase")]:
        app.transition_purchase(
            connection, purchase_id, person, grant(connection, person, action_code, purchase_id, activity), action,
        )
    receipt_grant = grant(connection, person, "receive_purchase", purchase_id, activity)
    first_receipt = app.receive_purchase(
        connection, purchase_id, str(line.id), 1, person, receipt_grant,
        idempotency_key="receipt-retry",
    )
    replay = app.receive_purchase(
        connection, purchase_id, str(line.id), 1, person, receipt_grant,
        idempotency_key="receipt-retry",
    )
    assert replay.purchase == first_receipt.purchase
    assert connection.execute("SELECT COUNT(*) FROM purchase_receipts").fetchone()[0] == 1
    assert replay.purchase.lines[0].received_quantity_units == 1


def test_create_retry_ignores_regenerated_internal_line_ids():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    create_grant = grant(connection, person, "create_purchase", activity, activity)
    first_line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=4, unit_label="box")
    first = app.create_purchase(
        connection, "supplier:1", activity, (first_line,), person, create_grant,
        idempotency_key="create-recreated-payload",
    )

    # A client retry may reconstruct the same business payload and receive new
    # in-memory UUIDs for line objects. Idempotency must return the original
    # persisted purchase rather than reject it or create duplicate lines.
    retried_line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=4, unit_label="box")
    assert retried_line.id != first_line.id
    replay = app.create_purchase(
        connection, "supplier:1", activity, (retried_line,), person, create_grant,
        idempotency_key="create-recreated-payload",
    )
    assert replay.purchase.id == first.purchase.id
    assert replay.purchase.lines[0].id == first_line.id
    assert connection.execute("SELECT COUNT(*) FROM purchases").fetchone()[0] == 1
    assert connection.execute("SELECT COUNT(*) FROM purchase_lines").fetchone()[0] == 1


def test_idempotency_key_reuse_with_different_payload_is_rejected():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    create_grant = grant(connection, person, "create_purchase", activity, activity)
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    app.create_purchase(
        connection, "supplier:1", activity, (line,), person, create_grant,
        idempotency_key="create-conflict",
    )
    other_line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=3, unit_label="piece")
    with pytest.raises(ValidationError):
        app.create_purchase(
            connection, "supplier:1", activity, (other_line,), person, create_grant,
            idempotency_key="create-conflict",
        )
    assert connection.execute("SELECT COUNT(*) FROM purchases").fetchone()[0] == 1
