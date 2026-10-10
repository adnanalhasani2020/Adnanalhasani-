"""Real two-connection race for authorized Sale lifecycle transitions.

Both workers load version 1 through the real persistence layer before either
may attempt its transition. The barrier is only a scheduling seam; all reads,
CAS updates, transaction handling, and history writes use SQLite and the real
SaleApplication implementation.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import sqlite3
import threading
import uuid

import pytest

from agent_core.application import SaleApplication
from agent_core.persistence import connect_database

NOW = "2026-10-10T00:00:00Z"
INSTANT = datetime(2026, 10, 10, tzinfo=timezone.utc)


def _id():
    return str(uuid.uuid4())


def _grant(db, actor_id, action, scope_ref, activity_id):
    grant_id = _id()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,"
        "action_code,scope_ref,context_ref,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,NULL,?,?,?,'active',?,NULL,?,?)",
        (grant_id, actor_id, action, scope_ref, activity_id, "2026-10-01T00:00:00Z", NOW, NOW),
    )
    return grant_id


def test_two_independent_connections_racing_sale_transitions_preserve_single_history_step(tmp_path):
    db_path = tmp_path / "sale-race.sqlite"
    db = connect_database(db_path)
    actor_id, activity_id, product_id, offering_id = _id(), _id(), _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, actor_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,'active','2026-10-01T00:00:00Z',NULL,?,?)",
        (offering_id, product_id, activity_id, NOW, NOW),
    )
    create_grant = _grant(db, actor_id, "create_sale", offering_id, activity_id)
    sale = SaleApplication().create_sale(
        db, offering_id, activity_id, actor_id, create_grant, now=INSTANT
    )
    confirm_grant = _grant(db, actor_id, "confirm_sale", str(sale.id), activity_id)
    cancel_grant = _grant(db, actor_id, "cancel_sale", str(sale.id), activity_id)
    db.commit()
    db.close()

    barrier = threading.Barrier(2)
    loaded_versions = []
    versions_lock = threading.Lock()

    class SynchronizedSaleApplication(SaleApplication):
        def _load_sale(self, connection, sale_id):
            loaded = super()._load_sale(connection, sale_id)
            with versions_lock:
                loaded_versions.append(loaded[2])
            # Both real DB reads complete before either transition can continue.
            barrier.wait(timeout=10)
            return loaded

    def run(action, grant_id):
        connection = connect_database(db_path)
        try:
            result = SynchronizedSaleApplication().transition_sale(
                connection, sale.id, actor_id, grant_id, action, now=INSTANT
            )
            return ("success", result.state.value)
        except (Exception) as exc:
            # SQLite can serialize the losing writer as a lock error rather
            # than allowing the CAS predicate to return zero rows. Both are
            # valid rejection outcomes, provided the losing transaction rolls back.
            return ("rejected", type(exc).__name__, str(exc))
        finally:
            connection.close()

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(
            lambda args: run(*args),
            [("confirm", confirm_grant), ("cancel", cancel_grant)],
        ))

    assert loaded_versions == [1, 1]
    assert sum(outcome[0] == "success" for outcome in outcomes) == 1, outcomes
    assert sum(outcome[0] == "rejected" for outcome in outcomes) == 1, outcomes

    verify = connect_database(db_path)
    state = verify.execute(
        "SELECT state, version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone()
    history = verify.execute(
        "SELECT current_version_ref, change_payload_ref FROM domain_history "
        "WHERE owner_domain='commerce' AND target_ref=? AND change_type='sale_state_transition' "
        "ORDER BY rowid",
        (str(sale.id),),
    ).fetchall()
    verify.close()

    assert state[0] in {"confirmed", "cancelled"}
    assert state[1] == 2
    assert len(history) == 2
    assert history[0] == (f"sale-state:{sale.id}:v1", "state:initiated")
    assert history[1][0] == f"sale-state:{sale.id}:v2"
    assert history[1][1] in {"state:confirmed", "state:cancelled"}
