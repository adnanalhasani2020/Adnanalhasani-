"""Product lifecycle reads must verify durable history without breaking legacy v1 reads."""
import uuid

import pytest

from agent_core.application import ProductApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-12T12:00:00Z"


def test_product_reopen_rejects_history_state_that_disagrees_with_entity(tmp_path):
    path = tmp_path / "product-history.sqlite"
    db = connect_database(path)
    app = ProductApplication()
    product = app.create_product(db, "Widget", actor_context_ref="actor-A", now=STAMP)
    app.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="actor-A", now=STAMP,
    )
    app.transition_product(
        db, product.product.id, "retire", expected_version=2,
        actor_context_ref="actor-A", now=STAMP,
    )
    db.commit()
    product_id = str(product.product.id)
    db.close()

    db = connect_database(path)
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:retire;state:active' "
        "WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='product_lifecycle' AND current_version_ref=?",
        (product_id, f"product:{product_id}:v3"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="Product history"):
        app.get_product(db, product_id)
    db.close()


def test_legacy_version_one_product_without_history_is_readable_but_not_transitionable():
    db = connect_database()
    product_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO products(product_id,name,state,created_at,updated_at,version_no) "
        "VALUES(?,?,'draft',?,?,1)",
        (product_id, "Legacy widget", STAMP, STAMP),
    )
    db.commit()

    app = ProductApplication()
    restored = app.get_product(db, product_id)
    assert restored.product.name == "Legacy widget"
    assert restored.version_no == 1

    with pytest.raises(ValidationError, match="explicit legacy reconciliation"):
        app.transition_product(
            db, product_id, "activate", expected_version=1,
            actor_context_ref="actor-A", now=STAMP,
        )
    db.close()


def test_product_reopen_rejects_history_action_that_conflicts_with_state(tmp_path):
    path = tmp_path / "product-history-action.sqlite"
    db = connect_database(path)
    app = ProductApplication()
    product = app.create_product(db, "Widget", actor_context_ref="actor-A", now=STAMP)
    app.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="actor-A", now=STAMP,
    )
    db.commit()
    product_id = str(product.product.id)
    db.close()

    db = connect_database(path)
    # The entity and recorded state both say active, but retiring cannot
    # produce the active state. A state-only consistency check misses this.
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:retire;state:active' "
        "WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='product_lifecycle' AND current_version_ref=?",
        (product_id, f"product:{product_id}:v2"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="action and state are inconsistent"):
        app.get_product(db, product_id)
    db.close()
