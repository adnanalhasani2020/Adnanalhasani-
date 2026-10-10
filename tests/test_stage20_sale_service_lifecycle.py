"""Sale creation must respect the lifecycle of a linked Service."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import SaleApplication
from agent_core.domain_commerce import SaleState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-10T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _service_sale_fixture(db, service_state):
    actor_id, activity_id, product_id, service_id, offering_id = (
        _id(), _id(), _id(), _id(), _id()
    )
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
        "INSERT INTO services(service_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (service_id, service_state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,service_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?)",
        (offering_id, product_id, service_id, activity_id, "active",
         "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    grant_id = _id()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,action_code,"
        "scope_ref,context_ref,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,NULL,'create_sale',?,?,'active','2026-10-01T00:00:00Z',NULL,?,?)",
        (grant_id, actor_id, offering_id, activity_id, STAMP, STAMP),
    )
    return actor_id, activity_id, offering_id, grant_id


@pytest.mark.parametrize("service_state", ["draft", "retired"])
def test_create_sale_rejects_service_backed_offering_when_service_is_not_active(service_state):
    db = connect_database()
    actor_id, activity_id, offering_id, grant_id = _service_sale_fixture(db, service_state)

    with pytest.raises(ValidationError, match="active Service"):
        SaleApplication().create_sale(
            db, offering_id, activity_id, actor_id, grant_id, now=NOW
        )

    assert db.execute("SELECT count(*) FROM sales").fetchone() == (0,)
    assert db.execute("SELECT count(*) FROM domain_history").fetchone() == (0,)
    db.close()


def test_create_sale_allows_service_backed_offering_when_service_is_active():
    db = connect_database()
    actor_id, activity_id, offering_id, grant_id = _service_sale_fixture(db, "active")

    sale = SaleApplication().create_sale(
        db, offering_id, activity_id, actor_id, grant_id, now=NOW
    )

    assert sale.state is SaleState.INITIATED
    assert db.execute(
        "SELECT offering_id, activity_id, state, version_no FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone() == (offering_id, activity_id, "initiated", 1)
    assert db.execute(
        "SELECT change_payload_ref FROM domain_history WHERE target_ref=?",
        (str(sale.id),),
    ).fetchall() == [("state:initiated",)]
    db.close()
