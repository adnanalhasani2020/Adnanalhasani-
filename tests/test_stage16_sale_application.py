"""Stage 16: integrated Sale application lifecycle, authorization, and persistence tests."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.application import InventoryApplication, SaleApplication
from agent_core.domain_commerce import SaleState
from agent_core.persistence import MIGRATIONS_DIR, connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-09T12:00:00Z"
ACTIONS = {
    "confirm": "confirm_sale",
    "fulfill": "fulfill_sale",
    "cancel": "cancel_sale",
    "return": "return_sale",
}


def _id():
    return str(uuid.uuid4())


def _fixture(db):
    actor_id, activity_id, product_id, offering_id = _id(), _id(), _id(), _id()
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (actor_id, "active", STAMP, STAMP))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity_id, actor_id, "active", STAMP, STAMP))
    db.execute("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (product_id, "active", STAMP, STAMP))
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    return actor_id, activity_id, product_id, offering_id


def _grant(db, actor_id, activity_id, scope_id, *, action="create_sale",
           state="active", starts="2026-10-01T00:00:00Z", ends=None, subject_id=None,
           context_id=None):
    subject = subject_id or actor_id
    if db.execute("SELECT 1 FROM persons WHERE person_id=?", (subject,)).fetchone() is None:
        db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
                   (subject, "active", STAMP, STAMP))
    grant_id = _id()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,action_code,"
        "scope_ref,context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,NULL,?,?,?,?,?,?,?,?,?)",
        (grant_id, subject, action, scope_id, context_id or activity_id, None,
         state, starts, ends, STAMP, STAMP),
    )
    return grant_id


def _create(app, db, actor_id, activity_id, offering_id, grant_id):
    return app.create_sale(db, offering_id, activity_id, actor_id, grant_id, now=NOW)


def _transition(app, db, action, sale, actor_id, grant_id):
    return app.transition_sale(db, sale.id, actor_id, grant_id, action, now=NOW)


def _sale_and_create_grant(db):
    actor, activity, _product, offering = _fixture(db)
    app = SaleApplication()
    grant = _grant(db, actor, activity, offering)
    sale = _create(app, db, actor, activity, offering, grant)
    return app, actor, activity, offering, sale, grant


def _transition_grant(db, actor, activity, sale, action, **kwargs):
    return _grant(db, actor, activity, str(sale.id), action=ACTIONS[action], **kwargs)


def test_e2e_sale_lifecycle_uses_application_authorization_and_round_trips(tmp_path):
    path = tmp_path / "sale-e2e.sqlite"
    db = connect_database(path)
    app, actor, activity, offering, sale, create_grant = _sale_and_create_grant(db)
    assert sale.state is SaleState.INITIATED

    # Create permission does not authorize confirm; every action needs its own grant.
    with pytest.raises(ValidationError, match="does not permit action confirm_sale"):
        app.confirm_sale(db, sale.id, actor, create_grant, now=NOW)

    confirm_grant = _transition_grant(db, actor, activity, sale, "confirm")
    sale = app.confirm_sale(db, sale.id, actor, confirm_grant, now=NOW)
    assert sale.state is SaleState.CONFIRMED

    fulfill_grant = _transition_grant(db, actor, activity, sale, "fulfill")
    sale = app.fulfill_sale(db, sale.id, actor, fulfill_grant, now=NOW)
    assert sale.state is SaleState.FULFILLED

    return_grant = _transition_grant(db, actor, activity, sale, "return")
    sale = app.return_sale(db, sale.id, actor, return_grant, now=NOW)
    assert sale.state is SaleState.RETURNED

    row = db.execute(
        "SELECT offering_id,activity_id,state,lifecycle_state,version_no FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone()
    assert row == (offering, activity, "returned", "returned", 4)
    db.close()

    db = connect_database(path)
    restored = app.get_sale(db, sale.id)
    assert restored.state is SaleState.RETURNED
    assert restored.history == (
        SaleState.INITIATED, SaleState.CONFIRMED, SaleState.FULFILLED, SaleState.RETURNED
    )
    records = db.execute(
        "SELECT actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref "
        "FROM domain_history WHERE target_ref=? ORDER BY rowid",
        (str(sale.id),),
    ).fetchall()
    assert [r[3] for r in records] == [
        "state:initiated", "state:confirmed", "state:fulfilled", "state:returned"
    ]
    assert records[0][1] is None
    assert all(records[i][1] == records[i-1][2] for i in range(1, len(records)))
    assert db.execute("SELECT count(*) FROM invoices WHERE sale_id=?", (str(sale.id),)).fetchone()[0] == 0
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "payments" not in tables or db.execute("SELECT count(*) FROM payments").fetchone()[0] == 0
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


@pytest.mark.parametrize("action", ["confirm", "fulfill", "cancel", "return"])
def test_each_transition_rejects_missing_or_wrong_grant(action):
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    with pytest.raises(ValidationError, match="persisted AuthorizationGrant"):
        app.transition_sale(db, sale.id, actor, _id(), action, now=NOW)
    wrong = _transition_grant(db, actor, activity, sale, "confirm" if action != "confirm" else "cancel")
    with pytest.raises(ValidationError, match="does not permit action"):
        app.transition_sale(db, sale.id, actor, wrong, action, now=NOW)
    assert db.execute("SELECT version_no FROM sales WHERE sale_id=?", (str(sale.id),)).fetchone()[0] == 1
    assert db.execute("SELECT count(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)).fetchone()[0] == 1
    db.close()


def test_transition_grant_must_match_actor_sale_activity_and_effective_period():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    wrong_actor = _transition_grant(db, actor, activity, sale, "confirm", subject_id=_id())
    wrong_scope = _grant(db, actor, activity, _id(), action="confirm_sale")
    wrong_context = _transition_grant(db, actor, activity, sale, "confirm", context_id=_id())
    expired = _transition_grant(
        db, actor, activity, sale, "confirm",
        starts="2026-10-01T00:00:00Z", ends="2026-10-08T00:00:00Z"
    )
    for grant in (wrong_actor, wrong_scope, wrong_context, expired):
        with pytest.raises(ValidationError):
            app.confirm_sale(db, sale.id, actor, grant, now=NOW)
    assert db.execute("SELECT version_no FROM sales WHERE sale_id=?", (str(sale.id),)).fetchone()[0] == 1
    assert db.execute("SELECT count(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)).fetchone()[0] == 1
    db.close()


def test_cancel_from_initiated_and_confirmed_but_reject_terminal_or_repeated_transitions():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    cancel_grant = _transition_grant(db, actor, activity, sale, "cancel")
    cancelled = app.cancel_sale(db, sale.id, actor, cancel_grant, now=NOW)
    assert cancelled.state is SaleState.CANCELLED
    with pytest.raises(ValidationError, match="Invalid Sale transition"):
        app.cancel_sale(db, sale.id, actor, cancel_grant, now=NOW)

    app2, actor2, activity2, _offering2, sale2, _ = _sale_and_create_grant(db)
    confirm_grant = _transition_grant(db, actor2, activity2, sale2, "confirm")
    app2.confirm_sale(db, sale2.id, actor2, confirm_grant, now=NOW)
    cancel_confirmed = _transition_grant(db, actor2, activity2, sale2, "cancel")
    assert app2.cancel_sale(db, sale2.id, actor2, cancel_confirmed, now=NOW).state is SaleState.CANCELLED
    db.close()


def test_fulfill_requires_confirmed_and_return_requires_fulfilled():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    fulfill = _transition_grant(db, actor, activity, sale, "fulfill")
    with pytest.raises(ValidationError, match="Invalid Sale transition"):
        app.fulfill_sale(db, sale.id, actor, fulfill, now=NOW)
    return_grant = _transition_grant(db, actor, activity, sale, "return")
    with pytest.raises(ValidationError, match="Invalid Sale transition"):
        app.return_sale(db, sale.id, actor, return_grant, now=NOW)
    db.close()


def test_history_failure_rolls_back_state_version_and_history():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    grant = _transition_grant(db, actor, activity, sale, "confirm")
    db.execute(
        "CREATE TRIGGER reject_transition_history BEFORE INSERT ON domain_history "
        "WHEN NEW.target_ref='" + str(sale.id) + "' AND NEW.change_payload_ref='state:confirmed' "
        "BEGIN SELECT RAISE(ABORT, 'forced history failure'); END"
    )
    with pytest.raises(sqlite3.IntegrityError, match="forced history failure"):
        app.confirm_sale(db, sale.id, actor, grant, now=NOW)
    assert db.execute("SELECT state,lifecycle_state,version_no FROM sales WHERE sale_id=?",
                      (str(sale.id),)).fetchone() == ("initiated", "initiated", 1)
    assert db.execute("SELECT count(*) FROM domain_history WHERE target_ref=?",
                      (str(sale.id),)).fetchone()[0] == 1
    db.close()


def test_fulfilled_uses_legacy_storage_label_but_canonical_domain_state_and_is_not_payment():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    confirm = _transition_grant(db, actor, activity, sale, "confirm")
    app.confirm_sale(db, sale.id, actor, confirm, now=NOW)
    fulfill = _transition_grant(db, actor, activity, sale, "fulfill")
    result = app.fulfill_sale(db, sale.id, actor, fulfill, now=NOW)
    assert result.state is SaleState.FULFILLED
    assert db.execute("SELECT state,lifecycle_state FROM sales WHERE sale_id=?",
                      (str(sale.id),)).fetchone() == ("completed", "fulfilled")
    assert app.get_sale(db, sale.id).state is SaleState.FULFILLED
    assert db.execute("SELECT count(*) FROM invoices WHERE sale_id=?", (str(sale.id),)).fetchone()[0] == 0
    db.close()


def test_persisted_lifecycle_reader_maps_legacy_and_rejects_conflicting_columns():
    assert SaleState.from_persisted("completed") is SaleState.FULFILLED
    assert SaleState.from_persisted("fulfilled", "fulfilled") is SaleState.FULFILLED
    assert SaleState.from_persisted("completed", "fulfilled") is SaleState.FULFILLED
    with pytest.raises(ValidationError, match="disagree"):
        SaleState.from_persisted("confirmed", "fulfilled")


def test_legacy_completed_row_is_not_rewritten_by_migration(tmp_path):
    path = tmp_path / "legacy-sale.sqlite"
    db = sqlite3.connect(path)
    db.execute("PRAGMA foreign_keys=ON")
    for migration_name in (
        "0001_initial_relational_schema.sql",
        "0002_prevent_person_canonical_cycles.sql",
        "0003_enforce_sale_offering_activity_consistency.sql",
    ):
        db.executescript((MIGRATIONS_DIR / migration_name).read_text(encoding="utf-8"))
        db.execute("INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(?,?)",
                   (migration_name.removesuffix(".sql"), STAMP))
    actor, activity, _product, offering = _fixture(db)
    sale_id = _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering, activity, "completed", STAMP, STAMP, STAMP),
    )
    db.commit()
    db.close()
    db = connect_database(path)
    assert db.execute("SELECT state,lifecycle_state FROM sales WHERE sale_id=?", (sale_id,)).fetchone() == ("completed", None)
    assert SaleState.from_persisted("completed", None) is SaleState.FULFILLED
    assert SaleApplication().get_sale_state(db, sale_id) is SaleState.FULFILLED
    db.close()


def test_missing_create_grant_fails_closed_and_leaves_no_partial_records():
    db = connect_database()
    actor, activity, _product, offering = _fixture(db)
    with pytest.raises(ValidationError, match="persisted AuthorizationGrant"):
        _create(SaleApplication(), db, actor, activity, offering, _id())
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_grant_scoped_to_one_sale_cannot_authorize_another_sale():
    db = connect_database()
    app, actor, activity, offering, first_sale, _ = _sale_and_create_grant(db)
    second_create_grant = _grant(db, actor, activity, offering)
    second_sale = _create(app, db, actor, activity, offering, second_create_grant)
    first_sale_confirm_grant = _transition_grant(db, actor, activity, first_sale, "confirm")

    with pytest.raises(ValidationError, match="scope does not match Sale"):
        app.confirm_sale(db, second_sale.id, actor, first_sale_confirm_grant, now=NOW)

    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(second_sale.id),)
    ).fetchone() == ("initiated", 1)
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE target_ref=?", (str(second_sale.id),)
    ).fetchone()[0] == 1
    db.close()


def test_grant_effective_period_compares_instants_across_timezone_offsets():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)

    # 13:00 +02:00 is 11:00 UTC, before the operation at 12:00 UTC.
    # 15:00 +02:00 is 13:00 UTC, after the operation.
    offset_grant = _transition_grant(
        db, actor, activity, sale, "confirm",
        starts="2026-10-09T13:00:00+02:00",
        ends="2026-10-09T15:00:00+02:00",
    )
    result = app.confirm_sale(db, sale.id, actor, offset_grant, now=NOW)
    assert result.state is SaleState.CONFIRMED
    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("confirmed", 2)
    db.close()


def test_grant_effective_period_rejects_expiry_at_same_instant_with_offset():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    # 14:00 +02:00 is exactly 12:00 UTC; effective_to is exclusive.
    expired_grant = _transition_grant(
        db, actor, activity, sale, "confirm",
        starts="2026-10-09T10:00:00+02:00",
        ends="2026-10-09T14:00:00+02:00",
    )
    with pytest.raises(ValidationError, match="outside its effective period"):
        app.confirm_sale(db, sale.id, actor, expired_grant, now=NOW)
    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("initiated", 1)
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
    ).fetchone()[0] == 1
    db.close()


def test_grant_effective_from_is_inclusive_at_exact_instant():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    grant = _transition_grant(
        db, actor, activity, sale, "confirm",
        starts="2026-10-09T14:00:00+02:00",
        ends="2026-10-09T16:00:00+02:00",
    )
    result = app.confirm_sale(db, sale.id, actor, grant, now=NOW)
    assert result.state is SaleState.CONFIRMED
    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("confirmed", 2)
    db.close()


@pytest.mark.parametrize(
    ("starts", "ends"),
    [
        ("not-a-timestamp", None),
        ("2026-10-09T10:00:00", None),
        ("2026-10-09T10:00:00Z", "not-a-timestamp"),
        ("2026-10-09T10:00:00Z", "2026-10-09T14:00:00"),
    ],
)
def test_malformed_or_timezone_naive_grant_bounds_fail_closed_without_mutation(starts, ends):
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    grant = _transition_grant(
        db, actor, activity, sale, "confirm", starts=starts, ends=ends
    )
    with pytest.raises(ValidationError, match="valid timezone-aware timestamp"):
        app.confirm_sale(db, sale.id, actor, grant, now=NOW)
    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("initiated", 1)
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
    ).fetchone()[0] == 1
    db.close()


def test_offering_effective_period_compares_instants_across_timezone_offsets():
    db = connect_database()
    actor, activity, _product, offering = _fixture(db)
    # 13:00 +02:00 is 11:00 UTC; 15:00 +02:00 is 13:00 UTC.
    db.execute(
        "UPDATE offerings SET effective_from=?, effective_to=? WHERE offering_id=?",
        ("2026-10-09T13:00:00+02:00", "2026-10-09T15:00:00+02:00", offering),
    )
    grant = _grant(db, actor, activity, offering)
    sale = _create(SaleApplication(), db, actor, activity, offering, grant)
    assert sale.state is SaleState.INITIATED
    assert db.execute(
        "SELECT count(*) FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone()[0] == 1
    db.close()


def test_offering_effective_period_rejects_expiry_at_same_instant_with_offset():
    db = connect_database()
    actor, activity, _product, offering = _fixture(db)
    # 14:00 +02:00 is exactly 12:00 UTC, the operation instant; expiry is exclusive.
    db.execute(
        "UPDATE offerings SET effective_from=?, effective_to=? WHERE offering_id=?",
        ("2026-10-09T10:00:00+02:00", "2026-10-09T14:00:00+02:00", offering),
    )
    grant = _grant(db, actor, activity, offering)
    with pytest.raises(ValidationError, match="Offering is outside its effective period"):
        _create(SaleApplication(), db, actor, activity, offering, grant)
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_sale_transition_fails_closed_when_persisted_version_exceeds_history_count():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    confirm_grant = _transition_grant(db, actor, activity, sale, "confirm")

    # Corrupt metadata: the Sale claims version 2 but has only its v1 history record.
    db.execute("UPDATE sales SET version_no=2 WHERE sale_id=?", (str(sale.id),))

    with pytest.raises(
        ValidationError,
        match="history entry count does not match the persisted Sale version",
    ):
        app.confirm_sale(db, sale.id, actor, confirm_grant, now=NOW)

    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("initiated", 1)
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
    ).fetchone()[0] == 1
    db.close()



def test_integrated_sale_period_boundary_and_offering_inventory_context():
    db = connect_database()
    actor, activity, product, offering = _fixture(db)
    db.execute(
        "UPDATE offerings SET effective_from=?, effective_to=? WHERE offering_id=?",
        ("2026-10-09T10:00:00Z", "2026-10-09T12:00:00Z", offering),
    )
    grant = _grant(db, actor, activity, offering)
    app = SaleApplication()
    sale = app.create_sale(
        db, offering, activity, actor, grant,
        now=datetime(2026, 10, 9, 11, 59, tzinfo=timezone.utc),
    )
    assert sale.state is SaleState.INITIATED
    assert db.execute(
        "SELECT offering_id, activity_id, state FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone() == (offering, activity, "initiated")

    stamp = "2026-10-09T12:00:00Z"
    _other_actor, other_activity, other_product, other_offering = _fixture(db)
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,"
        "scope_key,state,quantity_minor,observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        (_id(), activity, product, offering, "warehouse-A", "effective", 7,
         "2026-10-09T11:00:00Z", "2026-10-09T10:00:00Z", "2026-10-09T12:00:00Z",
         stamp, stamp),
    )
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,"
        "scope_key,state,quantity_minor,observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        (_id(), other_activity, other_product, other_offering, "warehouse-A", "effective", 999,
         "2026-10-09T11:30:00Z", "2026-10-09T10:00:00Z", "2026-10-09T12:00:00Z",
         stamp, stamp),
    )
    quantity = InventoryApplication().read_offering_quantity(
        db, offering, "warehouse-A",
        as_of=datetime(2026, 10, 9, 11, 45, tzinfo=timezone.utc),
    )
    assert quantity.status.value == "known"
    assert quantity.quantity_minor == 7
    assert quantity.scope_key == "warehouse-A"

    sales_before = db.execute("SELECT count(*) FROM sales").fetchone()[0]
    history_before = db.execute(
        "SELECT count(*) FROM domain_history WHERE owner_domain='commerce'"
    ).fetchone()[0]
    with pytest.raises(ValidationError, match="Offering is outside its effective period"):
        app.create_sale(
            db, offering, activity, actor, grant,
            now=datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc),
        )
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == sales_before
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE owner_domain='commerce'"
    ).fetchone()[0] == history_before
    assert db.execute(
        "SELECT state, version_no FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone() == ("initiated", 1)
    db.close()



def test_create_sale_history_write_failure_rolls_back_sale_and_history():
    db = connect_database()
    actor, activity, _product, offering = _fixture(db)
    grant = _grant(db, actor, activity, offering)
    db.commit()
    db.execute(
        "CREATE TRIGGER reject_initial_sale_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='commerce' AND NEW.change_payload_ref='state:initiated' "
        "BEGIN SELECT RAISE(ABORT, 'forced initial history failure'); END"
    )
    with pytest.raises(sqlite3.IntegrityError, match="forced initial history failure"):
        _create(SaleApplication(), db, actor, activity, offering, grant)
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute(
        "SELECT count(*) FROM domain_history WHERE owner_domain='commerce'"
    ).fetchone()[0] == 0
    db.close()


def test_rejected_repeated_sale_transition_preserves_state_version_and_history():
    db = connect_database()
    app, actor, activity, _offering, sale, _create_grant = _sale_and_create_grant(db)
    cancel_grant = _transition_grant(db, actor, activity, sale, "cancel")
    app.cancel_sale(db, sale.id, actor, cancel_grant, now=NOW)
    before_sale = db.execute(
        "SELECT state, lifecycle_state, version_no FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone()
    before_history = db.execute(
        "SELECT domain_history_id, prior_version_ref, current_version_ref, change_payload_ref "
        "FROM domain_history WHERE target_ref=? ORDER BY rowid",
        (str(sale.id),),
    ).fetchall()
    with pytest.raises(ValidationError, match="Invalid Sale transition"):
        app.cancel_sale(db, sale.id, actor, cancel_grant, now=NOW)
    assert db.execute(
        "SELECT state, lifecycle_state, version_no FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone() == before_sale
    assert db.execute(
        "SELECT domain_history_id, prior_version_ref, current_version_ref, change_payload_ref "
        "FROM domain_history WHERE target_ref=? ORDER BY rowid",
        (str(sale.id),),
    ).fetchall() == before_history
    db.close()


def test_inventory_read_with_only_wrong_offering_activity_context_is_read_only():
    db = connect_database()
    _actor, activity, _product, offering = _fixture(db)
    _other_actor, other_activity, _other_product, other_offering = _fixture(db)
    stamp = STAMP
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,offering_id,"
        "scope_key,state,quantity_minor,observed_at,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (_id(), other_activity, other_offering, "warehouse-A", "effective", 123,
         "2026-10-09T11:00:00Z", "2026-10-09T00:00:00Z", stamp, stamp),
    )
    before_positions = db.execute(
        "SELECT inventory_position_id, activity_id, offering_id, scope_key, state, quantity_minor, "
        "observed_at, effective_from, effective_to, version_no FROM inventory_positions ORDER BY rowid"
    ).fetchall()
    before_sales = db.execute("SELECT count(*) FROM sales").fetchone()[0]
    before_history = db.execute("SELECT count(*) FROM domain_history").fetchone()[0]
    result = InventoryApplication().read_offering_quantity(
        db, offering, "warehouse-A", as_of=NOW
    )
    assert result.status.value == "no_record"
    assert result.quantity_minor is None
    assert db.execute(
        "SELECT inventory_position_id, activity_id, offering_id, scope_key, state, quantity_minor, "
        "observed_at, effective_from, effective_to, version_no FROM inventory_positions ORDER BY rowid"
    ).fetchall() == before_positions
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == before_sales
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == before_history
    db.close()
