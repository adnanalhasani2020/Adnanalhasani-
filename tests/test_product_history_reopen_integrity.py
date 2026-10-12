"""Product lifecycle history remains consistent with the entity after SQLite reopen."""
import uuid

import pytest

from agent_core.application import ProductApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-12T12:00:00Z"


def test_product_reopen_rejects_latest_history_state_that_disagrees_with_entity(tmp_path):
    path = tmp_path / "product-history.sqlite"
    db = connect_database(path)
    app = ProductApplication()
    product = app.create_product(
        db, "History integrity product", actor_context_ref="history-test", now=STAMP
    )
    app.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="history-test", now=STAMP,
    )
    db.commit()
    product_id = str(product.product.id)
    db.close()

    db = connect_database(path)
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:retire;state:retired' "
        "WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='product_lifecycle' AND current_version_ref=?",
        (product_id, f"product:{product_id}:v2"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="Product history"):
        app.get_product(db, product_id)
    db.close()
