import sqlite3
from pathlib import Path
from uuid import uuid4

import pytest

from agent_core.domain_purchases import PurchaseLine, PurchaseState
from agent_core.purchase_application import PurchaseApplication
from agent_core.product_unit_application import ProductUnitApplication
from agent_core.shared import ValidationError


ROOT = Path(__file__).resolve().parents[1]


def database():
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript((ROOT / "schema/migrations/0001_initial_relational_schema.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0007_product_name.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0015_purchases.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0014_product_units.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0017_purchase_line_unit_snapshot.sql").read_text())
    connection.executescript((ROOT / "schema/migrations/0018_purchase_unit_snapshot_catalog_guards.sql").read_text())
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


def test_receipt_foreign_key_rejects_line_from_another_purchase():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    create_grant = grant(connection, person, "create_purchase", activity, activity)

    first_line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    first = app.create_purchase(
        connection, "supplier:1", activity, (first_line,), person, create_grant,
        idempotency_key="owner-check-1",
    )
    second_line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    second = app.create_purchase(
        connection, "supplier:2", activity, (second_line,), person, create_grant,
        idempotency_key="owner-check-2",
    )

    with pytest.raises(sqlite3.IntegrityError):
        connection.execute(
            "INSERT INTO purchase_receipts(receipt_id,purchase_id,purchase_line_id,quantity_units,"
            "received_at,actor_context_ref,idempotency_key,request_fingerprint) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid4()), str(first.purchase.id), str(second_line.id), 1,
             "2026-10-13T12:00:00Z", person, "bad-owner-receipt", "fingerprint"),
        )
    assert connection.execute("SELECT COUNT(*) FROM purchase_receipts").fetchone()[0] == 0


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


def test_purchase_history_rejects_receipt_id_mismatch():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    record = app.create_purchase(
        connection, "supplier:history-check", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="history-check-create",
    )
    purchase_id = str(record.purchase.id)
    for action, action_code in [
        ("submit", "submit_purchase"),
        ("approve", "approve_purchase"),
        ("order", "order_purchase"),
    ]:
        app.transition_purchase(
            connection, purchase_id, person,
            grant(connection, person, action_code, purchase_id, activity), action,
        )
    app.receive_purchase(
        connection, purchase_id, str(line.id), 1, person,
        grant(connection, person, "receive_purchase", purchase_id, activity),
        idempotency_key="history-check-receipt",
    )

    connection.execute(
        "UPDATE domain_history SET change_payload_ref=? "
        "WHERE target_ref=? AND change_type='purchase_state_transition' "
        "AND change_payload_ref LIKE 'state:partially_received;receipt:%'",
        (f"state:partially_received;receipt:{uuid4()}", purchase_id),
    )
    with pytest.raises(ValidationError, match="receipt identifiers disagree"):
        app.get_purchase(connection, purchase_id)


def test_purchase_restore_rejects_unsupported_persisted_state():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    record = app.create_purchase(
        connection, "supplier:corrupt-state", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="corrupt-state-create",
    )
    connection.execute("PRAGMA ignore_check_constraints=ON")
    connection.execute("UPDATE purchases SET state='void' WHERE purchase_id=?", (str(record.purchase.id),))
    connection.execute("PRAGMA ignore_check_constraints=OFF")
    with pytest.raises(ValidationError, match="unsupported persisted state"):
        app.get_purchase(connection, record.purchase.id)



def test_purchase_restore_rejects_malformed_persisted_line_identifier():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece")
    record = app.create_purchase(
        connection, "supplier:corrupt-line-id", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="corrupt-line-id-create",
    )
    connection.execute(
        "UPDATE purchase_lines SET purchase_line_id='not-a-uuid' WHERE purchase_id=?",
        (str(record.purchase.id),),
    )
    with pytest.raises(ValidationError, match="Persisted Purchase line identifier must be a valid UUID"):
        app.get_purchase(connection, record.purchase.id)


def test_purchase_restore_rejects_malformed_persisted_history_actor():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece"
    )
    record = app.create_purchase(
        connection, "supplier:history-actor", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="history-actor-create",
    )
    connection.execute(
        "UPDATE domain_history SET actor_context_ref='not-a-uuid' "
        "WHERE target_ref=? AND owner_domain='procurement' "
        "AND change_type='purchase_state_transition' AND current_version_ref=?",
        (str(record.purchase.id), f"purchase:{record.purchase.id}:v1"),
    )
    with pytest.raises(
        ValidationError,
        match="Persisted Purchase history actor identifier must be a valid UUID",
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()


def test_purchase_restore_rejects_creator_mismatch_with_initial_history_actor():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece"
    )
    record = app.create_purchase(
        connection, "supplier:history-creator", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="history-creator-create",
    )
    other_person = str(uuid4())
    connection.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (other_person, "active", "2026-10-13T12:00:00Z", "2026-10-13T12:00:00Z"),
    )
    connection.execute(
        "UPDATE domain_history SET actor_context_ref=? "
        "WHERE target_ref=? AND owner_domain='procurement' "
        "AND change_type='purchase_state_transition' AND current_version_ref=?",
        (other_person, str(record.purchase.id), f"purchase:{record.purchase.id}:v1"),
    )
    with pytest.raises(
        ValidationError, match="Purchase creator does not match initial history actor"
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()


def _purchase_with_one_receipt():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="piece"
    )
    record = app.create_purchase(
        connection, "supplier:receipt-integrity", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="receipt-integrity-create",
    )
    purchase_id = str(record.purchase.id)
    for action, action_code in (
        ("submit", "submit_purchase"), ("approve", "approve_purchase"), ("order", "order_purchase")
    ):
        record = app.transition_purchase(
            connection, purchase_id, person,
            grant(connection, person, action_code, purchase_id, activity), action,
        )
    record = app.receive_purchase(
        connection, purchase_id, str(line.id), 1, person,
        grant(connection, person, "receive_purchase", purchase_id, activity),
        idempotency_key="receipt-integrity-receive",
    )
    return connection, app, record


def test_purchase_restore_rejects_receipt_actor_that_disagrees_with_history():
    connection, app, record = _purchase_with_one_receipt()
    connection.execute("DROP TRIGGER trg_purchase_receipt_immutable_update")
    connection.execute(
        "UPDATE purchase_receipts SET actor_context_ref='not-a-uuid' WHERE purchase_id=?",
        (str(record.purchase.id),),
    )
    with pytest.raises(
        ValidationError, match="Persisted receipt actor identifier must be a valid UUID"
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()


def test_purchase_restore_rejects_receipt_idempotency_fingerprint_corruption():
    connection, app, record = _purchase_with_one_receipt()
    connection.execute("DROP TRIGGER trg_purchase_receipt_immutable_update")
    connection.execute(
        "UPDATE purchase_receipts SET request_fingerprint='corrupted' WHERE purchase_id=?",
        (str(record.purchase.id),),
    )
    with pytest.raises(
        ValidationError, match="Purchase receipt request fingerprint disagrees with persisted receipt facts"
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()


def test_purchase_restore_rejects_create_fingerprint_corruption():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product),
        quantity_units=2,
        unit_label="piece",
        unit_cost_minor=500,
        currency_code="YER",
    )
    record = app.create_purchase(
        connection, "supplier:fingerprint-integrity", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="create-fingerprint-integrity",
    )
    connection.execute(
        "UPDATE purchases SET create_request_fingerprint='corrupted' WHERE purchase_id=?",
        (str(record.purchase.id),),
    )
    with pytest.raises(
        ValidationError,
        match="Purchase create fingerprint disagrees with persisted Purchase facts",
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()


def test_purchase_restore_rejects_line_facts_that_disagree_with_create_fingerprint():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product),
        quantity_units=2,
        unit_label="piece",
        unit_cost_minor=500,
        currency_code="YER",
    )
    record = app.create_purchase(
        connection, "supplier:facts-integrity", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="create-facts-integrity",
    )
    connection.execute(
        "UPDATE purchase_lines SET quantity_units=3 WHERE purchase_id=?",
        (str(record.purchase.id),),
    )
    with pytest.raises(
        ValidationError,
        match="Purchase create fingerprint disagrees with persisted Purchase facts",
    ):
        app.get_purchase(connection, record.purchase.id)
    connection.close()

def test_purchase_line_unit_snapshot_uses_exact_rational_quantity():
    from fractions import Fraction
    from uuid import UUID

    line = PurchaseLine(
        product_id=UUID("00000000-0000-0000-0000-000000000001"),
        quantity_units=3,
        unit_label="case",
        product_unit_setting_id=UUID("00000000-0000-0000-0000-000000000002"),
        measurement_unit_id=UUID("00000000-0000-0000-0000-000000000003"),
        unit_code_snapshot="case",
        unit_name_snapshot="Case",
        unit_dimension_snapshot="count",
        conversion_numerator=12,
        conversion_denominator=1,
        product_unit_setting_version=2,
        measurement_unit_version=4,
    )
    assert line.base_quantity_units == Fraction(36, 1)


def test_purchase_line_rejects_incomplete_unit_snapshot():
    from uuid import UUID

    with pytest.raises(ValidationError, match="unit snapshot requires both"):
        PurchaseLine(
            product_id=UUID("00000000-0000-0000-0000-000000000001"),
            quantity_units=1,
            unit_label="case",
            product_unit_setting_id=UUID("00000000-0000-0000-0000-000000000002"),
        )


def test_purchase_line_snapshot_is_immutable_in_database():
    connection, person, activity, product = database()
    app = PurchaseApplication()
    line = PurchaseLine(product_id=__import__("uuid").UUID(product), quantity_units=2, unit_label="case")
    record = app.create_purchase(
        connection, "supplier:snapshot", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="snapshot-immutable",
    )
    with pytest.raises(sqlite3.IntegrityError, match="purchase unit snapshot is immutable"):
        connection.execute(
            "UPDATE purchase_lines SET unit_code_snapshot='changed' WHERE purchase_line_id=?",
            (str(line.id),),
        )
    assert app.get_purchase(connection, str(record.purchase.id)).purchase.lines[0].unit_code_snapshot is None


def test_purchase_line_snapshot_database_guard_rejects_partial_snapshot():
    connection, person, activity, product = database()
    with pytest.raises(sqlite3.IntegrityError, match="snapshot (must be complete|does not match active product unit configuration)"):
        connection.execute(
            "INSERT INTO purchase_lines(purchase_line_id,purchase_id,product_id,quantity_units,unit_label,"
            "received_quantity_units,created_at,product_unit_setting_id) VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid4()), str(uuid4()), product, 1, "case", 0, "2026-10-13T12:00:00Z", str(uuid4())),
        )
    # IDs and ratios without labels are still incomplete; NULL must not bypass the trigger.
    with pytest.raises(sqlite3.IntegrityError, match="snapshot (must be complete|does not match active product unit configuration)"):
        connection.execute(
            "INSERT INTO purchase_lines(purchase_line_id,purchase_id,product_id,quantity_units,unit_label,"
            "received_quantity_units,created_at,product_unit_setting_id,measurement_unit_id,"
            "conversion_numerator,conversion_denominator,product_unit_setting_version,measurement_unit_version) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()), str(uuid4()), product, 1, "case", 0, "2026-10-13T12:00:00Z",
             str(uuid4()), str(uuid4()), 12, 1, 1, 1),
        )

def test_purchase_creation_captures_explicit_unit_configuration_and_preserves_history():
    from fractions import Fraction

    connection, person, activity, product = database()
    units = ProductUnitApplication()
    unit_app = PurchaseApplication()
    base = units.create_unit(
        connection, code="pc", name="Piece", dimension="count",
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )
    case = units.create_unit(
        connection, code="case", name="Case", dimension="count",
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )
    units.configure_product_unit(
        connection, product, base.id, role="base",
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )
    units.configure_product_unit(
        connection, product, case.id, role="allowed",
        conversion_numerator=12, conversion_denominator=1,
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )

    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product), quantity_units=3, unit_label="case",
    )
    snapshot_line = unit_app.capture_unit_snapshot(connection, line, case.id)
    assert snapshot_line.base_quantity_units == Fraction(36, 1)
    record = unit_app.create_purchase(
        connection, "supplier:unit-snapshot", activity, (snapshot_line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="purchase-unit-snapshot",
    )
    restored = unit_app.get_purchase(connection, str(record.purchase.id)).purchase.lines[0]
    assert restored.product_unit_setting_id == snapshot_line.product_unit_setting_id
    assert restored.measurement_unit_id == case.id
    assert restored.conversion_numerator == 12
    assert restored.conversion_denominator == 1
    assert restored.base_quantity_units == Fraction(36, 1)

    # A later ratio update must not change the historical purchase line's conversion.
    current_setting = units.get_product_unit(connection, product, case.id)
    units.update_product_unit_conversion(
        connection, product, case.id, conversion_numerator=24, conversion_denominator=1,
        expected_version=current_setting.version_no,
        actor_context_ref=person, now="2026-10-14T12:00:00Z",
    )
    historical = unit_app.get_purchase(connection, str(record.purchase.id)).purchase.lines[0]
    assert historical.conversion_numerator == 12
    assert historical.base_quantity_units == Fraction(36, 1)

def test_database_rejects_snapshot_that_does_not_match_active_product_unit():
    connection, person, activity, product = database()
    units = ProductUnitApplication()
    unit = units.create_unit(
        connection, code="pc", name="Piece", dimension="count",
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )
    units.configure_product_unit(
        connection, product, unit.id, role="base",
        actor_context_ref=person, now="2026-10-13T12:00:00Z",
    )
    line = PurchaseLine(
        product_id=__import__("uuid").UUID(product), quantity_units=1, unit_label="pc",
    )
    record = PurchaseApplication().create_purchase(
        connection, "supplier:invalid-snapshot", activity, (line,), person,
        grant(connection, person, "create_purchase", activity, activity),
        idempotency_key="invalid-snapshot-owner",
    )
    with pytest.raises(sqlite3.IntegrityError, match="does not match active product unit configuration"):
        connection.execute(
            "INSERT INTO purchase_lines(purchase_line_id,purchase_id,product_id,quantity_units,unit_label,"
            "received_quantity_units,created_at,product_unit_setting_id,measurement_unit_id,unit_code_snapshot,"
            "unit_name_snapshot,unit_dimension_snapshot,conversion_numerator,conversion_denominator,"
            "product_unit_setting_version,measurement_unit_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()), str(record.purchase.id), product, 1, "pc", 0, "2026-10-13T12:00:00Z",
             str(uuid4()), str(unit.id), "pc", "Piece", "count", 1, 1, 1, 1),
        )


def test_purchase_receipt_evidence_cannot_be_updated_or_deleted_in_database():
    connection, app, record = _purchase_with_one_receipt()
    with pytest.raises(sqlite3.IntegrityError, match="purchase receipt evidence is immutable"):
        connection.execute(
            "UPDATE purchase_receipts SET actor_context_ref=? WHERE purchase_id=?",
            (str(uuid4()), str(record.purchase.id)),
        )
    with pytest.raises(sqlite3.IntegrityError, match="purchase receipt evidence is immutable"):
        connection.execute(
            "DELETE FROM purchase_receipts WHERE purchase_id=?",
            (str(record.purchase.id),),
        )
    assert len(app.get_purchase(connection, record.purchase.id).purchase.receipts) == 1
    connection.close()


def test_database_guard_rejects_receipt_event_not_reflected_in_received_quantity():
    connection, app, record = _purchase_with_one_receipt()
    receipt = record.purchase.receipts[0]
    with pytest.raises(
        sqlite3.IntegrityError,
        match="purchase receipt event must agree with persisted received quantity",
    ):
        connection.execute(
            "INSERT INTO purchase_receipts(receipt_id,purchase_id,purchase_line_id,quantity_units,"
            "received_at,actor_context_ref,idempotency_key,request_fingerprint) VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid4()), str(record.purchase.id), str(receipt.line_id), 1,
             "2026-10-16T12:00:00Z", str(uuid4()), "unexpected-direct-receipt", "placeholder"),
        )
    assert len(app.get_purchase(connection, record.purchase.id).purchase.receipts) == 1
    connection.close()
