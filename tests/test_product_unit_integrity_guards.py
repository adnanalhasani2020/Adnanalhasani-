"""Database-level integrity guards for product unit configuration."""
import uuid

import pytest

from agent_core.persistence import connect_database
from agent_core.product_unit_application import ProductUnitApplication


NOW = "2026-10-15T12:00:00Z"


def _product(db):
    product_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    db.commit()
    return product_id


def _configured_units():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(
        db, code="kg", name="Kilogram", dimension="mass",
        actor_context_ref="integrity-test", now=NOW,
    )
    gram = app.create_unit(
        db, code="g", name="Gram", dimension="mass",
        actor_context_ref="integrity-test", now=NOW,
    )
    litre = app.create_unit(
        db, code="L", name="Litre", dimension="volume",
        actor_context_ref="integrity-test", now=NOW,
    )
    app.configure_product_unit(
        db, product, kg.id, role="base",
        actor_context_ref="integrity-test", now=NOW,
    )
    app.configure_product_unit(
        db, product, gram.id, role="allowed", conversion_numerator=1,
        conversion_denominator=1000, actor_context_ref="integrity-test", now=NOW,
    )
    return db, app, product, kg, gram, litre


def test_direct_update_cannot_change_active_setting_to_another_dimension():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        with pytest.raises(Exception, match="dimension must match"):
            db.execute(
                "UPDATE product_unit_settings SET unit_id=? WHERE product_id=? AND unit_id=?",
                (str(litre.id), product, str(gram.id)),
            )
        db.rollback()
        assert app.get_product_unit(db, product, gram.id).unit_id == gram.id
    finally:
        db.close()


def test_direct_update_cannot_change_dimension_of_configured_unit():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        with pytest.raises(Exception, match="cannot change dimension"):
            db.execute(
                "UPDATE measurement_units SET dimension='volume' WHERE unit_id=?",
                (str(kg.id),),
            )
        db.rollback()
        assert app.get_unit(db, kg.id).dimension == "mass"
    finally:
        db.close()


def test_unit_records_are_retired_not_deleted():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        with pytest.raises(Exception, match="settings cannot be deleted"):
            db.execute(
                "DELETE FROM product_unit_settings WHERE product_id=? AND unit_id=?",
                (product, str(gram.id)),
            )
        db.rollback()
        with pytest.raises(Exception, match="cannot be deleted"):
            db.execute("DELETE FROM measurement_units WHERE unit_id=?", (str(kg.id),))
        db.rollback()
        assert app.get_unit(db, kg.id).state.value == "active"
        assert app.get_product_unit(db, product, gram.id).state.value == "active"
    finally:
        db.close()


def test_direct_update_cannot_activate_retired_measurement_unit():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        app.retire_product_unit(
            db, product, gram.id, actor_context_ref="integrity-test", now=NOW,
        )
        app.retire_unit(db, gram.id, actor_context_ref="integrity-test", now=NOW)
        with pytest.raises(Exception, match="retired measurement unit"):
            db.execute(
                "UPDATE product_unit_settings SET state='active' "
                "WHERE product_id=? AND unit_id=?",
                (product, str(gram.id)),
            )
        db.rollback()
        assert app.get_product_unit(db, product, gram.id).state.value == "retired"
    finally:
        db.close()

def test_retired_unit_dimension_is_immutable_when_history_references_it():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        app.retire_product_unit(
            db, product, gram.id, actor_context_ref="integrity-test", now=NOW,
        )
        with pytest.raises(Exception, match="cannot change dimension"):
            db.execute(
                "UPDATE measurement_units SET dimension='volume' WHERE unit_id=?",
                (str(gram.id),),
            )
        db.rollback()
        assert app.get_unit(db, gram.id).dimension == "mass"
    finally:
        db.close()


def test_database_rejects_non_integer_conversion_ratio_storage():
    db, app, product, kg, gram, litre = _configured_units()
    try:
        with pytest.raises(Exception, match="CHECK constraint failed"):
            db.execute(
                "UPDATE product_unit_settings SET conversion_numerator=9223372036854775808 "
                "WHERE product_id=? AND unit_id=?",
                (product, str(gram.id)),
            )
        db.rollback()
        assert app.get_product_unit(db, product, gram.id).conversion_numerator == 1
    finally:
        db.close()
