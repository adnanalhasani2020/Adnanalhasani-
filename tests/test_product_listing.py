"""Product list application tests."""
import pytest

from agent_core.application import ProductApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-15T08:00:00Z"


def test_list_products_filters_paginates_and_uses_stable_order():
    db = connect_database()
    app = ProductApplication()
    first = app.create_product(db, "First", actor_context_ref="catalog-admin", now=NOW)
    second = app.create_product(db, "Second", actor_context_ref="catalog-admin", now=NOW)
    third = app.create_product(db, "Third", actor_context_ref="catalog-admin", now=NOW)
    app.transition_product(
        db, second.product.id, "activate", expected_version=1,
        actor_context_ref="catalog-admin", now=NOW,
    )

    all_products = app.list_products(db)
    stable_ids = sorted([first.product.id, second.product.id, third.product.id], key=str)
    assert [item.product.id for item in all_products] == stable_ids
    assert [item.product.id for item in app.list_products(db, limit=1, offset=1)] == [
        stable_ids[1],
    ]
    assert [item.product.id for item in app.list_products(db, state="active")] == [
        second.product.id,
    ]
    assert app.list_products(db, state="retired") == ()
    db.close()


@pytest.mark.parametrize(
    "kwargs",
    [
        {"state": "deleted"},
        {"state": []},
        {"limit": 0},
        {"limit": 501},
        {"limit": True},
        {"offset": -1},
        {"offset": True},
    ],
)
def test_list_products_rejects_invalid_filters_and_pagination(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        ProductApplication().list_products(db, **kwargs)
    db.close()


def test_list_products_fails_closed_on_legacy_product_name_gap():
    db = connect_database()
    db.execute(
        "INSERT INTO products(product_id,name,state,created_at,updated_at,version_no) "
        "VALUES('legacy-product',NULL,'draft',?,?,1)",
        (NOW, NOW),
    )
    db.commit()
    with pytest.raises(ValidationError, match="explicit legacy reconciliation"):
        ProductApplication().list_products(db)
    db.close()
