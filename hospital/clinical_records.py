"""Unified encounter records and hospital operations workflows.

Development-only domain services. The HTTP layer must authorize every request.
"""
from __future__ import annotations

from .core import HospitalDB, HospitalError


def _encounter(conn, encounter_id: int, patient_id: int | None = None, *, open_only: bool = False):
    row = conn.execute("SELECT id, patient_id, status FROM encounters WHERE id=?", (encounter_id,)).fetchone()
    if row is None or (patient_id is not None and row["patient_id"] != patient_id):
        raise HospitalError("encounter not found for patient")
    if open_only and row["status"] != "open":
        raise HospitalError("an open encounter is required")
    return row


def add_diagnosis(db: HospitalDB, *, encounter_id: int, description: str, actor: str,
                  at: str, code: str = "", diagnosis_type: str = "clinical") -> int:
    description = description.strip()
    if not description or diagnosis_type not in {"clinical", "differential", "final"}:
        raise HospitalError("valid diagnosis description and type are required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        _encounter(conn, encounter_id)
        cur = conn.execute("""INSERT INTO encounter_diagnoses
            (encounter_id,code,description,diagnosis_type,recorded_by,recorded_at) VALUES(?,?,?,?,?,?)""",
            (encounter_id, code.strip(), description, diagnosis_type, actor, at))
        ident = int(cur.lastrowid)
        if diagnosis_type == "final":
            conn.execute("UPDATE encounters SET diagnosis=? WHERE id=?", (description, encounter_id))
        db._audit(conn, actor, "diagnosis.recorded", "encounter_diagnosis", ident, at,
                  {"encounter_id": encounter_id, "diagnosis_type": diagnosis_type})
        conn.commit()
        return ident


def record_allergy(db: HospitalDB, *, patient_id: int, substance: str, actor: str, at: str,
                   reaction: str = "", severity: str = "unknown", status: str = "active") -> int:
    substance = substance.strip()
    if not substance or severity not in {"unknown", "mild", "moderate", "severe"} or status not in {"active", "inactive"}:
        raise HospitalError("valid allergy substance, severity and status are required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        patient = conn.execute("SELECT id FROM patients WHERE id=? AND active=1", (patient_id,)).fetchone()
        if patient is None:
            raise HospitalError("active patient not found")
        try:
            cur = conn.execute("""INSERT INTO patient_allergies
                (patient_id,substance,reaction,severity,status,recorded_by,recorded_at) VALUES(?,?,?,?,?,?,?)""",
                (patient_id, substance, reaction.strip(), severity, status, actor, at))
        except Exception as exc:
            if "UNIQUE constraint failed" in str(exc):
                raise HospitalError("allergy already recorded for this patient; update the existing entry") from exc
            raise
        ident = int(cur.lastrowid)
        db._audit(conn, actor, "allergy.recorded", "patient_allergy", ident, at, {"patient_id": patient_id})
        conn.commit()
        return ident


def record_vitals(db: HospitalDB, *, encounter_id: int, measured_at: str, actor: str,
                  patient_id: int | None = None, temperature_c=None, pulse_bpm=None,
                  respiratory_rate=None, systolic_bp=None, diastolic_bp=None,
                  oxygen_saturation=None, weight_kg=None, height_cm=None, pain_score=None,
                  notes: str = "") -> int:
    values = {
        "temperature_c": temperature_c, "pulse_bpm": pulse_bpm, "respiratory_rate": respiratory_rate,
        "systolic_bp": systolic_bp, "diastolic_bp": diastolic_bp, "oxygen_saturation": oxygen_saturation,
        "weight_kg": weight_kg, "height_cm": height_cm, "pain_score": pain_score,
    }
    if all(value is None for value in values.values()):
        raise HospitalError("at least one vital sign is required")
    if pain_score is not None and not 0 <= int(pain_score) <= 10:
        raise HospitalError("pain_score must be between 0 and 10")
    bounds = {
        "temperature_c": (25, 45), "pulse_bpm": (0, 300), "respiratory_rate": (0, 100),
        "systolic_bp": (0, 350), "diastolic_bp": (0, 250), "oxygen_saturation": (0, 100),
        "weight_kg": (0, 1000), "height_cm": (0, 300),
    }
    for field, (minimum, maximum) in bounds.items():
        value = values[field]
        if value is not None:
            try:
                numeric = float(value)
            except (TypeError, ValueError) as exc:
                raise HospitalError(field + " must be numeric") from exc
            if numeric < minimum or numeric > maximum or (field in {"weight_kg", "height_cm"} and numeric <= 0):
                raise HospitalError(field + " is outside the accepted data-quality range")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        encounter = _encounter(conn, encounter_id, patient_id, open_only=True)
        pid = int(encounter["patient_id"])
        cur = conn.execute("""INSERT INTO vital_signs
            (encounter_id,patient_id,measured_at,measured_by,temperature_c,pulse_bpm,respiratory_rate,
             systolic_bp,diastolic_bp,oxygen_saturation,weight_kg,height_cm,pain_score,notes)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (encounter_id, pid, measured_at, actor, temperature_c, pulse_bpm, respiratory_rate,
             systolic_bp, diastolic_bp, oxygen_saturation, weight_kg, height_cm, pain_score, notes.strip()))
        ident = int(cur.lastrowid)
        db._audit(conn, actor, "vitals.recorded", "vital_signs", ident, measured_at, {"encounter_id": encounter_id})
        conn.commit()
        return ident


def add_nursing_note(db: HospitalDB, *, encounter_id: int, note: str, actor: str, at: str,
                     note_type: str = "observation") -> int:
    note = note.strip()
    allowed = {"observation", "care_plan", "handover", "medication", "incident"}
    if not note or note_type not in allowed:
        raise HospitalError("valid nursing note and note type are required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        encounter = _encounter(conn, encounter_id, open_only=True)
        cur = conn.execute("""INSERT INTO nursing_notes
            (encounter_id,patient_id,nurse,note_type,note,recorded_at) VALUES(?,?,?,?,?,?)""",
            (encounter_id, encounter["patient_id"], actor, note_type, note, at))
        ident = int(cur.lastrowid)
        db._audit(conn, actor, "nursing.note_added", "nursing_note", ident, at, {"encounter_id": encounter_id, "note_type": note_type})
        conn.commit()
        return ident


def adjust_stock(db: HospitalDB, *, medicine_id: int, quantity_delta: int, actor: str, at: str,
                 notes: str = "", movement_type: str = "adjustment") -> int:
    if not isinstance(quantity_delta, int) or isinstance(quantity_delta, bool) or quantity_delta == 0:
        raise HospitalError("quantity_delta must be a non-zero integer")
    if movement_type not in {"adjustment", "return", "waste"}:
        raise HospitalError("invalid stock movement type")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        medicine = conn.execute("SELECT quantity,active FROM medicines WHERE id=?", (medicine_id,)).fetchone()
        if medicine is None or not medicine["active"]:
            raise HospitalError("active medicine not found")
        if medicine["quantity"] + quantity_delta < 0:
            raise HospitalError("stock cannot become negative")
        conn.execute("UPDATE medicines SET quantity=quantity+? WHERE id=?", (quantity_delta, medicine_id))
        cur = conn.execute("""INSERT INTO inventory_movements
            (medicine_id,movement_type,quantity_delta,notes,actor,occurred_at) VALUES(?,?,?,?,?,?)""",
            (medicine_id, movement_type, quantity_delta, notes.strip(), actor, at))
        ident = int(cur.lastrowid)
        db._audit(conn, actor, "inventory.adjusted", "inventory_movement", ident, at,
                  {"medicine_id": medicine_id, "quantity_delta": quantity_delta})
        conn.commit()
        return ident


def create_supplier(db: HospitalDB, *, supplier_no: str, name: str, actor: str, at: str, phone: str = "") -> int:
    if not supplier_no.strip() or not name.strip():
        raise HospitalError("supplier number and name are required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute("INSERT INTO suppliers(supplier_no,name,phone,created_at) VALUES(?,?,?,?)",
                           (supplier_no.strip(), name.strip(), phone.strip(), at))
        ident = int(cur.lastrowid)
        db._audit(conn, actor, "supplier.created", "supplier", ident, at)
        conn.commit()
        return ident


def create_purchase_order(db: HospitalDB, *, order_no: str, supplier_id: int, items: list[dict],
                          actor: str, at: str, notes: str = "") -> int:
    if not order_no.strip() or not isinstance(items, list) or not items:
        raise HospitalError("order number and at least one item are required")
    normalized = []
    seen = set()
    for item in items:
        try:
            medicine_id, quantity, unit_cost = int(item["medicine_id"]), int(item["quantity"]), int(item["unit_cost_minor"])
        except (KeyError, TypeError, ValueError) as exc:
            raise HospitalError("each purchase item requires medicine_id, quantity and unit_cost_minor") from exc
        if medicine_id in seen or quantity <= 0 or unit_cost < 0:
            raise HospitalError("purchase items must be unique with positive quantity and non-negative unit cost")
        seen.add(medicine_id)
        normalized.append((medicine_id, quantity, unit_cost))
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        supplier = conn.execute("SELECT active FROM suppliers WHERE id=?", (supplier_id,)).fetchone()
        if supplier is None or not supplier["active"]:
            raise HospitalError("active supplier not found")
        for medicine_id, _, _ in normalized:
            medicine = conn.execute("SELECT active FROM medicines WHERE id=?", (medicine_id,)).fetchone()
            if medicine is None or not medicine["active"]:
                raise HospitalError("active medicine not found")
        cur = conn.execute("INSERT INTO purchase_orders(order_no,supplier_id,status,ordered_at,actor,notes) VALUES(?,?,'ordered',?,?,?)",
                           (order_no.strip(), supplier_id, at, actor, notes.strip()))
        ident = int(cur.lastrowid)
        conn.executemany("""INSERT INTO purchase_order_items
            (purchase_order_id,medicine_id,quantity,unit_cost_minor) VALUES(?,?,?,?)""",
            [(ident, *item) for item in normalized])
        db._audit(conn, actor, "purchase_order.created", "purchase_order", ident, at, {"item_count": len(normalized)})
        conn.commit()
        return ident


def receive_purchase_order(db: HospitalDB, *, order_id: int, actor: str, at: str) -> None:
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        order = conn.execute("SELECT status FROM purchase_orders WHERE id=?", (order_id,)).fetchone()
        if order is None or order["status"] != "ordered":
            raise HospitalError("ordered purchase order not found")
        items = conn.execute("SELECT medicine_id,quantity FROM purchase_order_items WHERE purchase_order_id=?", (order_id,)).fetchall()
        if not items:
            raise HospitalError("purchase order has no items")
        for item in items:
            conn.execute("UPDATE medicines SET quantity=quantity+? WHERE id=?", (item["quantity"], item["medicine_id"]))
            conn.execute("""INSERT INTO inventory_movements
                (medicine_id,movement_type,quantity_delta,reference_type,reference_id,actor,occurred_at)
                VALUES(?,'purchase',?,'purchase_order',?,?,?)""",
                (item["medicine_id"], item["quantity"], str(order_id), actor, at))
        conn.execute("UPDATE purchase_orders SET status='received',received_at=? WHERE id=?", (at, order_id))
        db._audit(conn, actor, "purchase_order.received", "purchase_order", order_id, at, {"item_count": len(items)})
        conn.commit()


def reports(db: HospitalDB) -> dict:
    with db.connect() as conn:
        return {
            "clinical": {
                "open_encounters": conn.execute("SELECT COUNT(*) FROM encounters WHERE status='open'").fetchone()[0],
                "diagnoses_recorded": conn.execute("SELECT COUNT(*) FROM encounter_diagnoses").fetchone()[0],
                "active_allergies": conn.execute("SELECT COUNT(*) FROM patient_allergies WHERE status='active'").fetchone()[0],
                "vital_observations": conn.execute("SELECT COUNT(*) FROM vital_signs").fetchone()[0],
                "nursing_notes": conn.execute("SELECT COUNT(*) FROM nursing_notes").fetchone()[0],
                "lab_orders_pending": conn.execute("SELECT COUNT(*) FROM lab_orders WHERE status IN ('ordered','collected','processing')").fetchone()[0],
            },
            "operations": {
                "beds_total": conn.execute("SELECT COUNT(*) FROM beds WHERE active=1").fetchone()[0],
                "beds_occupied": conn.execute("SELECT COUNT(DISTINCT bed_id) FROM admissions WHERE status='admitted'").fetchone()[0],
                "purchase_orders_pending": conn.execute("SELECT COUNT(*) FROM purchase_orders WHERE status='ordered'").fetchone()[0],
                "low_stock_medicines": conn.execute("SELECT COUNT(*) FROM medicines WHERE active=1 AND quantity<=reorder_level").fetchone()[0],
                "inventory_movements": conn.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0],
            },
            "finance": {
                "invoice_total_minor": conn.execute("SELECT COALESCE(SUM(total_minor),0) FROM invoices WHERE status!='void'").fetchone()[0],
                "payments_total_minor": conn.execute("SELECT COALESCE(SUM(amount_minor),0) FROM payments").fetchone()[0],
                "outstanding_minor": conn.execute("""SELECT COALESCE(SUM(i.total_minor-COALESCE(p.paid,0)),0)
                    FROM invoices i LEFT JOIN (SELECT invoice_id,SUM(amount_minor) AS paid FROM payments GROUP BY invoice_id) p
                    ON p.invoice_id=i.id WHERE i.status IN ('unpaid','partial')""").fetchone()[0],
            },
        }
