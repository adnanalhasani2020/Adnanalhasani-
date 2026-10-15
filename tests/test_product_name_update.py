"""Regression tests for audited Product name updates."""
import pytest

from agent_core.application import ProductApplication
from agent_core.domain_inventory import ProductState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-15T08:00:00Z"


def test_product_name_update_persists_history_and_restores_after_reopen(tmp_path):
    path = tmp_path / "rename.sqlite"
    db = connect_database(path)
    app = ProductApplication()
    created = app.create_product(db, "Bottle", actor_context_ref="catalog-admin", now=NOW)
    app.transition_product(
        db, created.product.id, "activate", expected_version=1,
        actor_context_ref="catalog-admin", now=NOW,
    )
    renamed = app.update_product_name(
        db, created.product.id, "  زجاجة ماء  ", expected_version=2,
        actor_context_ref="catalog-admin", now=NOW,
    )
    assert renamed.product.name == "زجاجة ماء"
    assert renamed.product.state is ProductState.ACTIVE
    assert renamed.version_no == 3
    db.close()

    db = connect_database(path)
    restored = app.get_product(db, created.product.id)
    assert restored.product.name == "زجاجة ماء"
    assert restored.product.state is ProductState.ACTIVE
    assert restored.version_no == 3
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref "
        "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='product_lifecycle' ORDER BY rowid",
        (str(created.product.id),),
    ).fetchall()
    assert history[-1] == (
        f"product:{created.product.id}:v2",
        f"product:{created.product.id}:v3",
        "action:rename;state:active;name:%D8%B2%D8%AC%D8%A7%D8%AC%D8%A9%20%D9%85%D8%A7%D8%A1",
    )
    db.close()


def test_product_name_update_rejects_blank_unchanged_and_stale_version():
    db = connect_database()
    app = ProductApplication()
    created = app.create_product(db, "Lamp", actor_context_ref="catalog-admin", now=NOW)
    with pytest.raises(ValidationError, match="name is required"):
        app.update_product_name(
            db, created.product.id, " ", expected_version=1,
            actor_context_ref="catalog-admin", now=NOW,
        )
    with pytest.raises(ValidationError, match="unchanged"):
        app.update_product_name(
            db, created.product.id, "Lamp", expected_version=1,
            actor_context_ref="catalog-admin", now=NOW,
        )
    app.update_product_name(
        db, created.product.id, "Desk Lamp", expected_version=1,
        actor_context_ref="catalog-admin", now=NOW,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.update_product_name(
            db, created.product.id, "Reading Lamp", expected_version=1,
            actor_context_ref="catalog-admin", now=NOW,
        )
    assert app.get_product(db, created.product.id).product.name == "Desk Lamp"
    db.close()


def test_product_rename_history_failure_rolls_back_name_and_version():
    import sqlite3

    db = connect_database()
    app = ProductApplication()
    created = app.create_product(db, "Before", actor_context_ref="catalog-admin", now=NOW)
    db.execute(
        "CREATE TRIGGER reject_product_rename_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='commerce' AND NEW.change_type='product_lifecycle' "
        "AND NEW.change_payload_ref LIKE 'action:rename;%' "
        "BEGIN SELECT RAISE(ABORT, 'rename history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="rename history unavailable"):
        app.update_product_name(
            db, created.product.id, "After", expected_version=1,
            actor_context_ref="catalog-admin", now=NOW,
        )
    current = app.get_product(db, created.product.id)
    assert current.product.name == "Before"
    assert current.version_no == 1
    db.close()

def test_product_restore_rejects_rename_history_that_disagrees_with_persisted_name():
    db = connect_database()
    app = ProductApplication()
    created = app.create_product(db, "Original", actor_context_ref="catalog-admin", now=NOW)
    app.update_product_name(
        db, created.product.id, "Canonical Name", expected_version=1,
        actor_context_ref="catalog-admin", now=NOW,
    )
    db.execute(
        "UPDATE domain_history SET change_payload_ref=? "
        "WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='product_lifecycle' AND change_payload_ref LIKE 'action:rename;%'",
        ("action:rename;state:draft;name:Forged%20Name", str(created.product.id)),
    )
    db.commit()
    with pytest.raises(ValidationError, match="does not agree with persisted rename history"):
        app.get_product(db, created.product.id)
    db.close()

def test_product_name_update_supports_multiple_renames_across_lifecycle_transitions():
    db = connect_database()
    app = ProductApplication()
    created = app.create_product(db, "Initial", actor_context_ref="catalog-admin", now=NOW)
    first = app.update_product_name(
        db, created.product.id, "Second Name", expected_version=1,
        actor_context_ref="catalog-admin", now=NOW,
    )
    second = app.update_product_name(
        db, created.product.id, "Third / Final", expected_version=first.version_no,
        actor_context_ref="catalog-admin", now=NOW,
    )
    active = app.transition_product(
        db, created.product.id, "activate", expected_version=second.version_no,
        actor_context_ref="catalog-admin", now=NOW,
    )
    restored = app.get_product(db, created.product.id)
    assert restored.product.name == "Third / Final"
    assert restored.product.state is ProductState.ACTIVE
    assert restored.version_no == active.version_no == 4
    db.close()
