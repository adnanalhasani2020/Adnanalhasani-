import uuid
from fractions import Fraction

import pytest

from agent_core.product_unit_application import ProductUnitApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


NOW = "2026-10-15T12:00:00Z"


def _product(db):
    pid = str(uuid.uuid4())
    now = NOW
    db.execute("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (pid,"active",now,now))
    db.commit()
    return pid


def test_unit_catalog_product_configuration_and_exact_conversion():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db,code="kg",name="Kilogram",dimension="mass",
                         actor_context_ref="test",now=NOW)
    gram = app.create_unit(db,code="g",name="Gram",dimension="mass",
                            actor_context_ref="test",now=NOW)
    app.configure_product_unit(db,product,kg.id,role="base",actor_context_ref="test",now=NOW)
    app.configure_product_unit(db,product,gram.id,role="allowed",conversion_numerator=1,
                                conversion_denominator=1000,actor_context_ref="test",now=NOW)
    assert app.convert(db,product,1000,gram.id,kg.id) == Fraction(1,1)
    assert app.convert(db,product,Fraction(3,2),kg.id,gram.id) == Fraction(1500,1)
    assert len(app.list_product_units(db,product)) == 2
    assert len(db.execute("SELECT * FROM domain_history WHERE change_type LIKE 'product_unit_%'").fetchall()) == 4
    db.close()


def test_rejects_cross_dimension_and_duplicate_base_and_bad_ratio():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db,code="kg",name="Kilogram",dimension="mass",actor_context_ref="test",now=NOW)
    litre = app.create_unit(db,code="L",name="Litre",dimension="volume",actor_context_ref="test",now=NOW)
    app.configure_product_unit(db,product,kg.id,role="base",actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="dimension"):
        app.configure_product_unit(db,product,litre.id,actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="already has an active base"):
        app.configure_product_unit(db,product,kg.id,role="base",actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="positive integer"):
        app.configure_product_unit(db,product,kg.id,conversion_numerator=0,actor_context_ref="test",now=NOW)
    db.close()


def test_requires_base_first_and_rejects_float_quantity():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db,code="kg",name="Kilogram",dimension="mass",actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="base unit"):
        app.configure_product_unit(db,product,kg.id,actor_context_ref="test",now=NOW)
    base = app.configure_product_unit(db,product,kg.id,role="base",actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="float"):
        app.convert(db,product,0.1,kg.id,kg.id)
    db.close()


def test_legacy_products_remain_without_assumed_units():
    db = connect_database()
    product = _product(db)
    assert db.execute("SELECT COUNT(*) FROM product_unit_settings WHERE product_id=?",(product,)).fetchone()[0] == 0
    db.close()


def test_unit_retirement_is_blocked_while_product_uses_it():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db,code="kg",name="Kilogram",dimension="mass",actor_context_ref="test",now=NOW)
    app.configure_product_unit(db,product,kg.id,role="base",actor_context_ref="test",now=NOW)
    with pytest.raises(ValidationError,match="configured for a product"):
        app.retire_unit(db,kg.id,actor_context_ref="test",now=NOW)
    db.close()



def test_unit_and_conversion_edits_are_versioned_and_audited():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db, code="kg", name="Kilogram", dimension="mass",
                         actor_context_ref="test", now=NOW)
    gram = app.create_unit(db, code="g", name="Gram", dimension="mass",
                           actor_context_ref="test", now=NOW)
    app.configure_product_unit(db, product, kg.id, role="base",
                               actor_context_ref="test", now=NOW)
    app.configure_product_unit(db, product, gram.id, role="allowed",
                               conversion_numerator=1, conversion_denominator=1000,
                               actor_context_ref="test", now=NOW)

    renamed = app.update_unit(db, kg.id, name="Kilogramme", expected_version=1,
                              actor_context_ref="test", now=NOW)
    assert renamed.name == "Kilogramme"
    assert renamed.version_no == 2
    renamed_again = app.update_unit(db, kg.id, code="KGM", expected_version=renamed.version_no,
                                    actor_context_ref="test", now=NOW)
    assert renamed_again.code == "KGM"
    assert renamed_again.version_no == 3
    with pytest.raises(ValidationError, match="version conflict"):
        app.update_unit(db, kg.id, name="Stale Name", expected_version=1,
                        actor_context_ref="test", now=NOW)

    updated = app.update_product_unit_conversion(
        db, product, gram.id, conversion_numerator=1, conversion_denominator=500,
        expected_version=1, actor_context_ref="test", now=NOW
    )
    assert updated.version_no == 2
    assert app.convert(db, product, 500, gram.id, kg.id) == Fraction(1, 1)
    with pytest.raises(ValidationError, match="base unit conversion ratio must be 1/1"):
        app.update_product_unit_conversion(
            db, product, kg.id, conversion_numerator=2, conversion_denominator=1,
            expected_version=1, actor_context_ref="test", now=NOW
        )
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type LIKE 'product_unit_%'"
    ).fetchone()[0] == 7
    db.close()


def test_product_unit_retirement_preserves_base_invariant():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db, code="kg", name="Kilogram", dimension="mass",
                         actor_context_ref="test", now=NOW)
    gram = app.create_unit(db, code="g", name="Gram", dimension="mass",
                           actor_context_ref="test", now=NOW)
    app.configure_product_unit(db, product, kg.id, role="base",
                               actor_context_ref="test", now=NOW)
    app.configure_product_unit(db, product, gram.id, role="allowed",
                               conversion_numerator=1, conversion_denominator=1000,
                               actor_context_ref="test", now=NOW)
    with pytest.raises(ValidationError, match="retire active allowed units"):
        app.retire_product_unit(db, product, kg.id, actor_context_ref="test", now=NOW)
    retired = app.retire_product_unit(db, product, gram.id,
                                      expected_version=1, actor_context_ref="test", now=NOW)
    assert retired.state.value == "retired"
    retired_base = app.retire_product_unit(db, product, kg.id,
                                           expected_version=1, actor_context_ref="test", now=NOW)
    assert retired_base.state.value == "retired"
    db.close()



def test_database_guards_reject_dimension_drift_and_active_unit_retirement():
    db = connect_database()
    app = ProductUnitApplication()
    product = _product(db)
    kg = app.create_unit(db, code="kg", name="Kilogram", dimension="mass",
                         actor_context_ref="test", now=NOW)
    litre = app.create_unit(db, code="L", name="Litre", dimension="volume",
                            actor_context_ref="test", now=NOW)
    app.configure_product_unit(db, product, kg.id, role="base",
                               actor_context_ref="test", now=NOW)

    with pytest.raises(Exception, match="dimension must match"):
        db.execute(
            "INSERT INTO product_unit_settings(product_unit_setting_id,product_id,unit_id,role,"
            "conversion_numerator,conversion_denominator,state,created_at,updated_at,configured_by_ref) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), product, str(litre.id), "allowed", 1, 1,
             "active", NOW, NOW, "test"),
        )
    db.rollback()

    with pytest.raises(Exception, match="configured for a product"):
        db.execute("UPDATE measurement_units SET state='retired' WHERE unit_id=?", (str(kg.id),))
    db.rollback()
    assert app.get_unit(db, kg.id).state.value == "active"
    db.close()


def test_unit_code_uniqueness_is_case_insensitive_and_retire_version_is_validated():
    db = connect_database()
    app = ProductUnitApplication()
    unit = app.create_unit(db, code="kg", name="Kilogram", dimension="mass",
                            actor_context_ref="test", now=NOW)
    with pytest.raises(ValidationError, match="already exists"):
        app.create_unit(db, code="KG", name="Duplicate Kilogram", dimension="mass",
                        actor_context_ref="test", now=NOW)
    with pytest.raises(ValidationError, match="positive integer"):
        app.retire_unit(db, unit.id, expected_version=True,
                        actor_context_ref="test", now=NOW)
    assert app.get_unit(db, unit.id).state.value == "active"
    db.close()
