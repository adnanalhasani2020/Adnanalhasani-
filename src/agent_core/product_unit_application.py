"""Persisted measurement-unit catalog and product-unit configuration.

Ratios are exact rational values expressed as base units per one configured unit.
This module does not mutate inventory or transaction records.
"""
from contextlib import contextmanager
from datetime import datetime, timezone
from fractions import Fraction
from uuid import UUID, uuid4

from agent_core.domain_product_units import (
    MeasurementUnit, ProductUnitRole, ProductUnitSetting, UnitState, convert_quantity,
)
from agent_core.shared import ValidationError


_MAX_SQLITE_INTEGER = 2**63 - 1


def _now(value=None):
    if value is None:
        value = datetime.now(timezone.utc)
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError("timestamp must be valid ISO-8601") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError("timestamp must be timezone-aware")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _uuid(value, label):
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValidationError(f"{label} must be a valid UUID") from exc


@contextmanager
def _atomic(db):
    if db.in_transaction:
        name = "unit_operation_" + uuid4().hex
        db.execute(f"SAVEPOINT {name}")
        try:
            yield
        except BaseException:
            db.execute(f"ROLLBACK TO SAVEPOINT {name}")
            db.execute(f"RELEASE SAVEPOINT {name}")
            raise
        else:
            db.execute(f"RELEASE SAVEPOINT {name}")
        return
    db.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        db.rollback()
        raise
    else:
        db.commit()


class ProductUnitApplication:
    def create_unit(self, db, *, code, name, dimension, actor_context_ref, now=None, unit_id=None):
        code = code.strip().upper() if isinstance(code, str) else ""
        name = name.strip() if isinstance(name, str) else ""
        dimension = dimension.strip().lower() if isinstance(dimension, str) else ""
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        if not code or not name or not dimension or not actor:
            raise ValidationError("code, name, dimension, and actor_context_ref are required")
        if len(code) > 32 or any(ch.isspace() for ch in code):
            raise ValidationError("unit code must be at most 32 characters and contain no whitespace")
        key, stamp = _uuid(unit_id or uuid4(), "unit_id"), _now(now)
        with _atomic(db):
            try:
                db.execute(
                    "INSERT INTO measurement_units(unit_id,code,name,dimension,state,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?,?)",
                    (key, code, name, dimension, "active", stamp, stamp),
                )
            except Exception as exc:
                if "UNIQUE constraint failed" in str(exc):
                    raise ValidationError("unit code already exists") from exc
                raise
            self._history(db, "measurement_unit", key, 1, None, "create", actor, stamp,
                          f"code:{code};dimension:{dimension};state:active")
        return self.get_unit(db, key)

    def update_unit(self, db, unit_id, *, actor_context_ref, expected_version=1,
                    code=None, name=None, now=None):
        key, stamp = _uuid(unit_id, "unit_id"), _now(now)
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        if not actor:
            raise ValidationError("actor_context_ref is required")
        if code is None and name is None:
            raise ValidationError("at least one of code or name must be provided")
        if code is not None:
            code = code.strip().upper() if isinstance(code, str) else ""
            if not code or len(code) > 32 or any(ch.isspace() for ch in code):
                raise ValidationError("unit code must be at most 32 characters and contain no whitespace")
        if name is not None:
            name = name.strip() if isinstance(name, str) else ""
            if not name:
                raise ValidationError("unit name must be non-empty")
        if not isinstance(expected_version, int) or isinstance(expected_version, bool) or expected_version < 1:
            raise ValidationError("expected_version must be a positive integer")
        with _atomic(db):
            row = db.execute(
                "SELECT code,name,state,version_no FROM measurement_units WHERE unit_id=?", (key,)
            ).fetchone()
            if row is None:
                raise ValidationError("measurement unit does not exist")
            if row[3] != expected_version:
                raise ValidationError("measurement unit version conflict")
            if row[2] != "active":
                raise ValidationError("retired measurement units cannot be edited")
            new_code, new_name = code if code is not None else row[0], name if name is not None else row[1]
            try:
                db.execute(
                    "UPDATE measurement_units SET code=?,name=?,updated_at=?,version_no=version_no+1 "
                    "WHERE unit_id=? AND version_no=?",
                    (new_code,new_name,stamp,key,expected_version),
                )
            except Exception as exc:
                if "UNIQUE constraint failed" in str(exc):
                    raise ValidationError("unit code already exists") from exc
                raise
            self._history(db, "measurement_unit", key, expected_version + 1, expected_version,
                          "update", actor, stamp, f"code:{new_code};name:{new_name}")
        return self.get_unit(db, key)

    def get_unit(self, db, unit_id):
        key = _uuid(unit_id, "unit_id")
        row = db.execute(
            "SELECT unit_id,code,name,dimension,state,version_no FROM measurement_units WHERE unit_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("measurement unit does not exist")
        return MeasurementUnit(row[1], row[2], row[3], UUID(row[0]), UnitState(row[4]), row[5])

    def list_units(self, db, *, dimension=None, include_retired=False):
        sql = "SELECT unit_id,code,name,dimension,state,version_no FROM measurement_units WHERE 1=1"
        params = []
        if dimension is not None:
            if not isinstance(dimension, str) or not dimension.strip():
                raise ValidationError("dimension must be a non-empty string")
            sql += " AND dimension=?"
            params.append(dimension.strip().lower())
        if not include_retired:
            sql += " AND state='active'"
        sql += " ORDER BY dimension,code,unit_id"
        return [MeasurementUnit(r[1], r[2], r[3], UUID(r[0]), UnitState(r[4]), r[5])
                for r in db.execute(sql, params).fetchall()]

    def retire_unit(self, db, unit_id, *, expected_version=1, actor_context_ref, now=None):
        key, stamp = _uuid(unit_id, "unit_id"), _now(now)
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        if not actor:
            raise ValidationError("actor_context_ref is required")
        if not isinstance(expected_version, int) or isinstance(expected_version, bool) or expected_version < 1:
            raise ValidationError("expected_version must be a positive integer")
        with _atomic(db):
            row = db.execute(
                "SELECT state,version_no FROM measurement_units WHERE unit_id=?", (key,)
            ).fetchone()
            if row is None:
                raise ValidationError("measurement unit does not exist")
            if row[1] != expected_version:
                raise ValidationError("measurement unit version conflict")
            if row[0] == "retired":
                return self.get_unit(db, key)
            if db.execute("SELECT 1 FROM product_unit_settings WHERE unit_id=? AND state='active'", (key,)).fetchone():
                raise ValidationError("unit is configured for a product and cannot be retired")
            db.execute(
                "UPDATE measurement_units SET state='retired',updated_at=?,version_no=version_no+1 "
                "WHERE unit_id=? AND version_no=?", (stamp,key,expected_version)
            )
            self._history(db, "measurement_unit", key, expected_version + 1, expected_version,
                          "retire", actor, stamp, "state:retired")
        return self.get_unit(db, key)

    def configure_product_unit(self, db, product_id, unit_id, *, role="allowed",
                               conversion_numerator=1, conversion_denominator=1,
                               actor_context_ref, now=None):
        product, unit, stamp = _uuid(product_id, "product_id"), _uuid(unit_id, "unit_id"), _now(now)
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        try:
            role = ProductUnitRole(role)
        except (ValueError, TypeError) as exc:
            raise ValidationError("role must be base or allowed") from exc
        if not actor:
            raise ValidationError("actor_context_ref is required")
        if (not isinstance(conversion_numerator, int) or isinstance(conversion_numerator, bool)
                or not isinstance(conversion_denominator, int) or isinstance(conversion_denominator, bool)
                or conversion_numerator <= 0 or conversion_denominator <= 0
                or conversion_numerator > _MAX_SQLITE_INTEGER
                or conversion_denominator > _MAX_SQLITE_INTEGER):
            raise ValidationError("conversion ratio must use positive integer numerator and denominator")
        if role is ProductUnitRole.BASE and (conversion_numerator, conversion_denominator) != (1,1):
            raise ValidationError("base unit conversion ratio must be 1/1")
        setting_id = str(uuid4())
        with _atomic(db):
            product_row = db.execute("SELECT product_id FROM products WHERE product_id=?", (product,)).fetchone()
            if product_row is None:
                raise ValidationError("product does not exist")
            unit_row = db.execute(
                "SELECT dimension,state FROM measurement_units WHERE unit_id=?", (unit,)
            ).fetchone()
            if unit_row is None:
                raise ValidationError("measurement unit does not exist")
            if unit_row[1] != "active":
                raise ValidationError("retired measurement unit cannot be configured")
            existing = db.execute(
                "SELECT dimension FROM product_unit_settings s JOIN measurement_units u ON u.unit_id=s.unit_id "
                "WHERE s.product_id=? AND s.state='active' LIMIT 1", (product,)
            ).fetchone()
            if existing and existing[0] != unit_row[0]:
                raise ValidationError("unit dimension must match the product's existing unit dimension")
            if role is ProductUnitRole.ALLOWED:
                base = db.execute(
                    "SELECT 1 FROM product_unit_settings WHERE product_id=? AND role='base' AND state='active'",
                    (product,),
                ).fetchone()
                if not base:
                    raise ValidationError("configure a base unit before adding allowed units")
            elif db.execute(
                "SELECT 1 FROM product_unit_settings WHERE product_id=? AND role='base' AND state='active'",
                (product,),
            ).fetchone():
                raise ValidationError("product already has an active base unit; rebasing is not supported")
            try:
                db.execute(
                    "INSERT INTO product_unit_settings(product_unit_setting_id,product_id,unit_id,role,"
                    "conversion_numerator,conversion_denominator,state,created_at,updated_at,configured_by_ref) "
                    "VALUES(?,?,?,?,?,?, 'active',?,?,?)",
                    (setting_id,product,unit,role.value,conversion_numerator,conversion_denominator,
                     stamp,stamp,actor),
                )
            except Exception as exc:
                if "UNIQUE constraint failed" in str(exc):
                    raise ValidationError("unit is already configured for this product") from exc
                raise
            self._history(db, "product_unit_setting", setting_id, 1, None, "configure", actor, stamp,
                          f"product:{product};unit:{unit};role:{role.value};ratio:{conversion_numerator}/{conversion_denominator}")
        return self.get_product_unit(db, product, unit)

    def update_product_unit_conversion(self, db, product_id, unit_id, *,
                                       conversion_numerator, conversion_denominator,
                                       actor_context_ref, expected_version=1, now=None):
        product, unit, stamp = _uuid(product_id, "product_id"), _uuid(unit_id, "unit_id"), _now(now)
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        if not actor:
            raise ValidationError("actor_context_ref is required")
        if (not isinstance(conversion_numerator, int) or isinstance(conversion_numerator, bool)
                or not isinstance(conversion_denominator, int) or isinstance(conversion_denominator, bool)
                or conversion_numerator <= 0 or conversion_denominator <= 0):
            raise ValidationError("conversion ratio must use positive integer numerator and denominator")
        if not isinstance(expected_version, int) or isinstance(expected_version, bool) or expected_version < 1:
            raise ValidationError("expected_version must be a positive integer")
        with _atomic(db):
            row = db.execute(
                "SELECT role,state,version_no FROM product_unit_settings WHERE product_id=? AND unit_id=?",
                (product,unit),
            ).fetchone()
            if row is None:
                raise ValidationError("product unit setting does not exist")
            if row[2] != expected_version:
                raise ValidationError("product unit setting version conflict")
            if row[1] != "active":
                raise ValidationError("retired product unit settings cannot be edited")
            if row[0] == "base":
                if (conversion_numerator, conversion_denominator) != (1, 1):
                    raise ValidationError("base unit conversion ratio must be 1/1")
                raise ValidationError("base unit conversion ratio is fixed")
            db.execute(
                "UPDATE product_unit_settings SET conversion_numerator=?,conversion_denominator=?,"
                "updated_at=?,version_no=version_no+1 WHERE product_id=? AND unit_id=? AND version_no=?",
                (conversion_numerator,conversion_denominator,stamp,product,unit,expected_version),
            )
            setting_id = db.execute(
                "SELECT product_unit_setting_id FROM product_unit_settings WHERE product_id=? AND unit_id=?",
                (product,unit),
            ).fetchone()[0]
            self._history(
                db, "product_unit_setting", setting_id, expected_version + 1, expected_version,
                "update_conversion", actor, stamp,
                f"product:{product};unit:{unit};ratio:{conversion_numerator}/{conversion_denominator}",
            )
        return self.get_product_unit(db, product, unit)

    def retire_product_unit(self, db, product_id, unit_id, *,
                            actor_context_ref, expected_version=1, now=None):
        product, unit, stamp = _uuid(product_id, "product_id"), _uuid(unit_id, "unit_id"), _now(now)
        actor = actor_context_ref.strip() if isinstance(actor_context_ref, str) else ""
        if not actor:
            raise ValidationError("actor_context_ref is required")
        if not isinstance(expected_version, int) or isinstance(expected_version, bool) or expected_version < 1:
            raise ValidationError("expected_version must be a positive integer")
        with _atomic(db):
            row = db.execute(
                "SELECT product_unit_setting_id,role,state,version_no FROM product_unit_settings "
                "WHERE product_id=? AND unit_id=?", (product,unit)
            ).fetchone()
            if row is None:
                raise ValidationError("product unit setting does not exist")
            if row[3] != expected_version:
                raise ValidationError("product unit setting version conflict")
            if row[2] == "retired":
                return self.get_product_unit(db, product, unit)
            if row[1] == "base" and db.execute(
                "SELECT 1 FROM product_unit_settings WHERE product_id=? AND state='active' "
                "AND role='allowed' LIMIT 1", (product,)
            ).fetchone():
                raise ValidationError("retire active allowed units before retiring the base unit")
            db.execute(
                "UPDATE product_unit_settings SET state='retired',updated_at=?,version_no=version_no+1 "
                "WHERE product_id=? AND unit_id=? AND version_no=?",
                (stamp,product,unit,expected_version),
            )
            self._history(
                db, "product_unit_setting", row[0], expected_version + 1, expected_version,
                "retire", actor, stamp, f"product:{product};unit:{unit};state:retired",
            )
        return self.get_product_unit(db, product, unit)

    def get_product_unit(self, db, product_id, unit_id):
        product, unit = _uuid(product_id, "product_id"), _uuid(unit_id, "unit_id")
        row = db.execute(
            "SELECT s.product_id,s.unit_id,s.role,s.conversion_numerator,s.conversion_denominator,"
            "s.state,s.version_no,u.dimension FROM product_unit_settings s "
            "JOIN measurement_units u ON u.unit_id=s.unit_id "
            "WHERE s.product_id=? AND s.unit_id=?", (product,unit)
        ).fetchone()
        if row is None:
            raise ValidationError("product unit setting does not exist")
        setting = ProductUnitSetting(UUID(row[0]),UUID(row[1]),ProductUnitRole(row[2]),
                                     row[3],row[4],UnitState(row[5]),row[6])
        return setting

    def list_product_units(self, db, product_id, *, include_retired=False):
        product = _uuid(product_id, "product_id")
        sql = ("SELECT s.product_id,s.unit_id,s.role,s.conversion_numerator,s.conversion_denominator,"
               "s.state,s.version_no FROM product_unit_settings s WHERE s.product_id=?")
        params = [product]
        if not include_retired:
            sql += " AND s.state='active'"
        sql += " ORDER BY CASE s.role WHEN 'base' THEN 0 ELSE 1 END, s.unit_id"
        return [ProductUnitSetting(UUID(r[0]),UUID(r[1]),ProductUnitRole(r[2]),r[3],r[4],
                                   UnitState(r[5]),r[6])
                for r in db.execute(sql,params).fetchall()]

    def convert(self, db, product_id, quantity, source_unit_id, target_unit_id):
        source = self.get_product_unit(db, product_id, source_unit_id)
        target = self.get_product_unit(db, product_id, target_unit_id)
        dimensions = db.execute(
            "SELECT unit_id,dimension FROM measurement_units WHERE unit_id IN (?,?)",
            (str(source.unit_id), str(target.unit_id)),
        ).fetchall()
        dimensions_by_unit = {row[0]: row[1] for row in dimensions}
        if (dimensions_by_unit.get(str(source.unit_id)) is None
                or dimensions_by_unit.get(str(target.unit_id)) is None
                or dimensions_by_unit[str(source.unit_id)] != dimensions_by_unit[str(target.unit_id)]):
            raise ValidationError("unit dimensions must match for conversion")
        return convert_quantity(quantity, source, target)

    @staticmethod
    def _history(db, target_type, target_id, version, prior_version, action, actor, stamp, payload):
        db.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()),"commerce",f"{target_type}:{target_id}",f"product_unit_{action}",stamp,actor,
             None if prior_version is None else f"{target_type}:{target_id}:v{prior_version}",
             f"{target_type}:{target_id}:v{version}",payload,stamp),
        )
