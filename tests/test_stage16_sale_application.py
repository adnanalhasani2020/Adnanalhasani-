"""Stage 16 integrated authorization, persistence, and legacy lifecycle verification."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.application import SaleApplication
from agent_core.domain_authorization import AuthorizationGrant
from agent_core.domain_commerce import SaleState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-09T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _fixture(db):
    actor_id, activity_id, product_id, offering_id = _id(), _id(), _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    return actor_id, activity_id, product_id, offering_id


def _grant(actor_id, offering_id, *, action="create_sale", scope=None, active=True):
    grant = AuthorizationGrant(
        uuid.UUID(actor_id), action, scope if scope is not None else offering_id
    )
    if active:
        grant.activate()
    return grant


def _create(app, db, actor_id, activity_id, offering_id, grant):
    return app.create_sale(
        db, offering_id, activity_id, actor_id, grant, now=NOW
    )


def test_e2e_authorized_sale_round_trips_history_and_fulfilled_is_not_payment(tmp_path):
    path = tmp_path / "stage16-sale-e2e.sqlite"
    db = connect_database(path)
    actor_id, activity_id, _product_id, offering_id = _fixture(db)
    grant = _grant(actor_id, offering_id)

    sale = _create(SaleApplication(), db, actor_id, activity_id, offering_id, grant)
    assert sale.state is SaleState.INITIATED
    assert db.execute(
        "SELECT offering_id,activity_id,state,lifecycle_state FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone() == (offering_id, activity_id, "initiated", "initiated")
    assert db.execute(
        "SELECT owner_domain,target_ref,change_type,actor_context_ref,prior_version_ref,current_version_ref "
        "FROM domain_history WHERE target_ref=?",
        (str(sale.id),),
    ).fetchone() == (
        "commerce", str(sale.id), "sale_state_transition", actor_id, None,
        f"sale-state:{sale.id}:v1",
    )

    # Persist the established lifecycle vocabulary. The trigger maps the historical
    # storage label to the canonical business state without rewriting old records.
    with db:
        for prior_ref, current_ref, state in (
            (f"sale-state:{sale.id}:v1", f"sale-state:{sale.id}:v2", "confirmed"),
            (f"sale-state:{sale.id}:v2", f"sale-state:{sale.id}:v3", "completed"),
        ):
            db.execute(
                "UPDATE sales SET state=?,updated_at=? WHERE sale_id=?",
                (state, STAMP, str(sale.id)),
            )
            db.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (_id(), "commerce", str(sale.id), "sale_state_transition", STAMP,
                 actor_id, prior_ref, current_ref, STAMP),
            )
    assert db.execute(
        "SELECT state,lifecycle_state FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("completed", "fulfilled")
    assert SaleState.from_persisted("completed", "fulfilled") is SaleState.FULFILLED

    # Reopen from disk and verify the persisted sale/history survived as one coherent path.
    db.close()
    db = connect_database(path)
    restored = db.execute(
        "SELECT sale_id,offering_id,activity_id,state,lifecycle_state FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone()
    history = db.execute(
        "SELECT actor_context_ref,prior_version_ref,current_version_ref FROM domain_history "
        "WHERE target_ref=?",
        (str(sale.id),),
    ).fetchall()
    assert restored == (
        str(sale.id), offering_id, activity_id, "completed", "fulfilled"
    )
    assert history == [
        (actor_id, None, f"sale-state:{sale.id}:v1"),
        (actor_id, f"sale-state:{sale.id}:v1", f"sale-state:{sale.id}:v2"),
        (actor_id, f"sale-state:{sale.id}:v2", f"sale-state:{sale.id}:v3"),
    ]
    assert db.execute("SELECT count(*) FROM invoices WHERE sale_id=?", (str(sale.id),)).fetchone()[0] == 0
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "payments" not in tables or db.execute("SELECT count(*) FROM payments").fetchone()[0] == 0
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_authorization_requires_active_exact_actor_action_and_offering_scope():
    db = connect_database()
    actor_id, activity_id, _product_id, offering_id = _fixture(db)
    app = SaleApplication()

    wrong_actor = _grant(_id(), offering_id)
    wrong_action = _grant(actor_id, offering_id, action="read")
    wrong_scope = _grant(actor_id, _id())
    inactive = _grant(actor_id, offering_id, active=False)
    for grant in (wrong_actor, wrong_action, wrong_scope, inactive):
        with pytest.raises(ValidationError):
            _create(app, db, actor_id, activity_id, offering_id, grant)
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_authorization_does_not_transfer_between_activities_or_offerings():
    db = connect_database()
    actor_id, activity_id, product_id, offering_id = _fixture(db)
    other_activity, other_product, other_offering = _id(), _id(), _id()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (other_activity, actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (other_product, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (other_offering, other_product, activity_id, "active", "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    grant = _grant(actor_id, offering_id)
    app = SaleApplication()

    # Activity context cannot be substituted, even if the actor has a grant for the original offering.
    with pytest.raises(ValidationError):
        _create(app, db, actor_id, other_activity, offering_id, grant)
    # A grant for one offering does not authorize another offering in the same activity.
    with pytest.raises(ValidationError, match="scope"):
        _create(app, db, actor_id, activity_id, other_offering, grant)
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_rejections_leave_no_partial_sale_or_history():
    db = connect_database()
    actor_id, activity_id, _product_id, offering_id = _fixture(db)
    app = SaleApplication()
    grant = _grant(actor_id, offering_id)

    with pytest.raises(ValidationError, match="must match Offering Activity"):
        _create(app, db, actor_id, _id(), offering_id, grant)
    db.execute("UPDATE offerings SET state='withdrawn' WHERE offering_id=?", (offering_id,))
    with pytest.raises(ValidationError, match="active Offering"):
        _create(app, db, actor_id, activity_id, offering_id, grant)

    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_persisted_lifecycle_reader_maps_legacy_and_rejects_conflicting_columns():
    assert SaleState.from_persisted("completed") is SaleState.FULFILLED
    assert SaleState.from_persisted("fulfilled", "fulfilled") is SaleState.FULFILLED
    assert SaleState.from_persisted("completed", "fulfilled") is SaleState.FULFILLED
    with pytest.raises(ValidationError, match="disagree"):
        SaleState.from_persisted("confirmed", "fulfilled")


def test_legacy_completed_row_remains_unmodified_and_resolves_to_fulfilled(tmp_path):
    from pathlib import Path
    from agent_core.persistence import MIGRATIONS_DIR, apply_migrations

    path = tmp_path / "legacy-sale.sqlite"
    db = sqlite3.connect(path)
    db.execute("PRAGMA foreign_keys=ON")
    for migration_name in (
        "0001_initial_relational_schema.sql",
        "0002_prevent_person_canonical_cycles.sql",
        "0003_enforce_sale_offering_activity_consistency.sql",
    ):
        db.executescript((MIGRATIONS_DIR / migration_name).read_text(encoding="utf-8"))
        db.execute(
            "INSERT INTO schema_migrations(version,applied_at) VALUES(?,?)",
            (migration_name.removesuffix(".sql"), STAMP),
        )
    actor_id, activity_id, _product_id, offering_id = _fixture(db)
    sale_id = _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "completed", STAMP, STAMP, STAMP),
    )
    db.commit()
    db.close()

    # Migration 0004 must preserve the old state and leave lifecycle_state NULL.
    db = connect_database(path)
    assert db.execute(
        "SELECT state,lifecycle_state FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone() == ("completed", None)
    assert SaleState.from_persisted("completed", None) is SaleState.FULFILLED
    db.close()
