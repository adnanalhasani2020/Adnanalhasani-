"""Stage 16: integrated Sale application lifecycle, authorization, and persistence tests."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.application import SaleApplication
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
        starts="2026-10-10T00:00:00Z", ends="2026-10-11T00:00:00Z"
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
    db.close()


def test_missing_create_grant_fails_closed_and_leaves_no_partial_records():
    db = connect_database()
    actor, activity, _product, offering = _fixture(db)
    with pytest.raises(ValidationError, match="persisted AuthorizationGrant"):
        _create(SaleApplication(), db, actor, activity, offering, _id())
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()
