"""Additional transactional clinical, laboratory and pharmacy workflows.

These functions extend HospitalDB without claiming clinical validation. Callers must
authenticate/authorize the actor before invoking them.
"""
from __future__ import annotations

from datetime import datetime, timezone

from .core import HospitalDB, HospitalError

def _parse_utc(value: str, field: str) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise HospitalError(f"{field} must be a valid ISO-8601 timestamp")
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError as exc:
        raise HospitalError(f"{field} must be a valid ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)



def create_encounter(db: HospitalDB, *, patient_id: int, doctor_id: int | None,
                     opened_at: str, actor: str, chief_complaint: str = "",
                     appointment_id: int | None = None) -> int:
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        patient = conn.execute("SELECT active FROM patients WHERE id=?", (patient_id,)).fetchone()
        if patient is None or not patient["active"]:
            raise HospitalError("active patient not found")
        if doctor_id is not None:
            doctor = conn.execute("SELECT active FROM doctors WHERE id=?", (doctor_id,)).fetchone()
            if doctor is None or not doctor["active"]:
                raise HospitalError("active doctor not found")
        if appointment_id is not None:
            appt = conn.execute("SELECT patient_id,doctor_id,status FROM appointments WHERE id=?",
                                (appointment_id,)).fetchone()
            if appt is None or appt["patient_id"] != patient_id or appt["status"] not in ("scheduled", "checked_in"):
                raise HospitalError("appointment is not eligible for this encounter")
            if doctor_id is not None and appt["doctor_id"] != doctor_id:
                raise HospitalError("encounter doctor must match the appointment doctor")
        cur = conn.execute("""INSERT INTO encounters
            (patient_id,doctor_id,appointment_id,opened_at,chief_complaint)
            VALUES(?,?,?,?,?)""", (patient_id, doctor_id, appointment_id, opened_at, chief_complaint))
        ident = cur.lastrowid
        if appointment_id is not None:
            conn.execute("UPDATE appointments SET status='checked_in' WHERE id=?", (appointment_id,))
        db._audit(conn, actor, "encounter.opened", "encounter", ident, opened_at)
        conn.commit()
        return int(ident)


def close_encounter(db: HospitalDB, *, encounter_id: int, closed_at: str,
                    actor: str, diagnosis: str, clinical_notes: str = "") -> None:
    if not diagnosis.strip():
        raise HospitalError("diagnosis is required to close an encounter")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT opened_at,status,appointment_id FROM encounters WHERE id=?",
                           (encounter_id,)).fetchone()
        if row is None or row["status"] != "open":
            raise HospitalError("open encounter not found")
        if closed_at < row["opened_at"]:
            raise HospitalError("close time cannot precede open time")
        conn.execute("""UPDATE encounters SET closed_at=?,diagnosis=?,clinical_notes=?,status='closed'
            WHERE id=?""", (closed_at, diagnosis.strip(), clinical_notes, encounter_id))
        if row["appointment_id"] is not None:
            conn.execute("UPDATE appointments SET status='completed' WHERE id=?",
                         (row["appointment_id"],))
        db._audit(conn, actor, "encounter.closed", "encounter", encounter_id, closed_at)
        conn.commit()


def order_lab_test(db: HospitalDB, *, patient_id: int, test_name: str,
                   ordered_at: str, actor: str, doctor_id: int | None = None,
                   encounter_id: int | None = None) -> int:
    if not isinstance(test_name, str) or not test_name.strip():
        raise HospitalError("test name is required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        patient = conn.execute("SELECT active FROM patients WHERE id=?", (patient_id,)).fetchone()
        if patient is None or not patient["active"]:
            raise HospitalError("active patient not found")
        if doctor_id is not None:
            doctor = conn.execute("SELECT active FROM doctors WHERE id=?", (doctor_id,)).fetchone()
            if doctor is None or not doctor["active"]:
                raise HospitalError("active ordering doctor not found")
        if encounter_id is not None:
            encounter = conn.execute("SELECT patient_id,doctor_id,status FROM encounters WHERE id=?",
                                     (encounter_id,)).fetchone()
            if encounter is None or encounter["patient_id"] != patient_id or encounter["status"] != "open":
                raise HospitalError("an open encounter for this patient is required")
            if doctor_id is not None and encounter["doctor_id"] != doctor_id:
                raise HospitalError("ordering doctor must match the encounter doctor")
        _parse_utc(ordered_at, "ordered_at")
        cur = conn.execute("""INSERT INTO lab_orders
            (patient_id,encounter_id,ordered_by,test_name,ordered_at)
            VALUES(?,?,?,?,?)""", (patient_id, encounter_id, doctor_id, test_name.strip(), ordered_at))
        ident = cur.lastrowid
        conn.execute("INSERT INTO lab_order_events(lab_order_id,status,occurred_at,actor) VALUES(?,?,?,?)",
                     (ident, "ordered", ordered_at, actor))
        db._audit(conn, actor, "lab.ordered", "lab_order", ident, ordered_at)
        conn.commit()
        return int(ident)


def update_lab_status(db: HospitalDB, *, order_id: int, status: str,
                      actor: str, at: str, result: str | None = None) -> None:
    transitions = {
        "ordered": {"collected", "cancelled"},
        "collected": {"processing", "cancelled"},
        "processing": {"resulted", "cancelled"},
    }
    if status not in {"collected", "processing", "resulted", "cancelled"}:
        raise HospitalError("invalid lab status")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT status,ordered_at FROM lab_orders WHERE id=?", (order_id,)).fetchone()
        if row is None:
            raise HospitalError("lab order not found")
        event_time = _parse_utc(at, "at")
        last_event = conn.execute(
            "SELECT occurred_at FROM lab_order_events WHERE lab_order_id=? ORDER BY id DESC LIMIT 1",
            (order_id,),
        ).fetchone()
        previous_time = last_event["occurred_at"] if last_event is not None else row["ordered_at"]
        if event_time < _parse_utc(previous_time, "previous lab event timestamp"):
            raise HospitalError("lab status timestamp cannot precede the previous lab event")
        if status not in transitions.get(row["status"], set()):
            raise HospitalError(f"invalid lab status transition: {row['status']} -> {status}")
        if status == "resulted" and (not isinstance(result, str) or not result.strip()):
            raise HospitalError("a text result is required to mark the test resulted")
        conn.execute("""UPDATE lab_orders SET status=?,result=?,resulted_at=?
            WHERE id=?""", (status, result if status == "resulted" else None,
                            at if status == "resulted" else None, order_id))
        conn.execute("INSERT INTO lab_order_events(lab_order_id,status,occurred_at,actor) VALUES(?,?,?,?)",
                     (order_id, status, at, actor))
        db._audit(conn, actor, "lab.status_changed", "lab_order", order_id, at, {"status": status})
        conn.commit()


def add_medicine(db: HospitalDB, *, sku: str, name: str, quantity: int,
                 reorder_level: int, actor: str, at: str, unit: str = "unit") -> int:
    if not isinstance(sku, str) or not isinstance(name, str) or not sku.strip() or not name.strip():
        raise HospitalError("valid SKU and medicine name are required")
    if type(quantity) is not int or type(reorder_level) is not int or quantity < 0 or reorder_level < 0:
        raise HospitalError("stock quantity and reorder level must be non-negative integers")
    if not isinstance(unit, str) or not unit.strip():
        raise HospitalError("medicine unit is required")
    _parse_utc(at, "at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute("""INSERT INTO medicines(sku,name,quantity,reorder_level,unit)
            VALUES(?,?,?,?,?)""", (sku.strip(), name.strip(), quantity, reorder_level, unit))
        ident = cur.lastrowid
        conn.execute(
            """INSERT INTO medicine_stock_movements
               (medicine_id,movement_type,quantity_delta,balance_after,source_key,actor,occurred_at)
               VALUES(?,?,?,?,?,?,?)""",
            (ident, "initial_stock", quantity, quantity, f"medicine:create:{ident}", actor, at),
        )
        batch_no = f"OPENING-{ident}"
        batch_cur = conn.execute(
            """INSERT INTO medicine_batches
               (medicine_id,batch_no,expiry_date,quantity,received_at,reference,created_by)
               VALUES(?,?,NULL,?,?,?,?)""",
            (ident, batch_no, quantity, at, "opening stock; expiry not supplied", actor),
        )
        if quantity > 0:
            conn.execute(
                """INSERT INTO medicine_batch_movements
                   (batch_id,medicine_id,movement_type,quantity_delta,balance_after,reference,actor,occurred_at)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (batch_cur.lastrowid, ident, "initial_stock", quantity, quantity,
                 "opening stock; expiry not supplied", actor, at),
            )
        db._audit(conn, actor, "medicine.created", "medicine", ident, at,
                  {"initial_quantity": quantity})
        conn.commit()
        return int(ident)


def restock_medicine(db: HospitalDB, *, medicine_id: int, quantity: int,
                     actor: str, at: str, reference: str = "") -> int:
    """Receive stock atomically and write an audit event."""
    if type(medicine_id) is not int or medicine_id <= 0:
        raise HospitalError("medicine_id must be a positive integer")
    if type(quantity) is not int or quantity <= 0:
        raise HospitalError("restock quantity must be a positive integer")
    _parse_utc(at, "at")
    if not isinstance(reference, str):
        raise HospitalError("reference must be text")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT quantity,active FROM medicines WHERE id=?", (medicine_id,)).fetchone()
        if row is None or not row["active"]:
            raise HospitalError("active medicine not found")
        new_balance = int(row["quantity"] + quantity)
        conn.execute("UPDATE medicines SET quantity=? WHERE id=?", (new_balance, medicine_id))
        conn.execute(
            """INSERT INTO medicine_stock_movements
               (medicine_id,movement_type,quantity_delta,balance_after,reference,actor,occurred_at)
               VALUES(?,?,?,?,?,?,?)""",
            (medicine_id, "restock", quantity, new_balance, reference.strip(), actor, at),
        )
        next_batch = conn.execute(
            "SELECT COALESCE(MAX(id),0)+1 FROM medicine_batches"
        ).fetchone()[0]
        batch_no = f"UNTRACKED-{medicine_id}-{next_batch}"
        batch_cur = conn.execute(
            """INSERT INTO medicine_batches
               (medicine_id,batch_no,expiry_date,quantity,received_at,reference,created_by)
               VALUES(?,?,NULL,?,?,?,?)""",
            (medicine_id, batch_no, quantity, at,
             reference.strip() or "restock received without expiry details", actor),
        )
        conn.execute(
            """INSERT INTO medicine_batch_movements
               (batch_id,medicine_id,movement_type,quantity_delta,balance_after,reference,actor,occurred_at)
               VALUES(?,?,?,?,?,?,?,?)""",
            (batch_cur.lastrowid, medicine_id, "restock", quantity, quantity,
             reference.strip() or "expiry not supplied", actor, at),
        )
        db._audit(conn, actor, "medicine.stock_received", "medicine", medicine_id, at,
                  {"quantity_added": quantity, "reference": reference.strip(),
                   "balance_after": new_balance})
        conn.commit()
        return int(row["quantity"] + quantity)


def receive_medicine_batch(db: HospitalDB, *, medicine_id: int, batch_no: str,
                           quantity: int, expiry_date: str, actor: str, at: str,
                           reference: str = "") -> dict[str, int | str]:
    """Receive a traceable batch and update aggregate and lot stock atomically."""
    if type(medicine_id) is not int or medicine_id <= 0:
        raise HospitalError("medicine_id must be a positive integer")
    if type(quantity) is not int or quantity <= 0:
        raise HospitalError("batch quantity must be a positive integer")
    if not isinstance(batch_no, str) or not batch_no.strip() or len(batch_no.strip()) > 100:
        raise HospitalError("batch number is required and must be at most 100 characters")
    if not isinstance(expiry_date, str) or len(expiry_date) != 10:
        raise HospitalError("expiry_date must use YYYY-MM-DD")
    try:
        parsed_expiry = datetime.strptime(expiry_date, "%Y-%m-%d").date()
    except ValueError as exc:
        raise HospitalError("expiry_date must be a valid YYYY-MM-DD date") from exc
    if parsed_expiry.isoformat() != expiry_date:
        raise HospitalError("expiry_date must use YYYY-MM-DD")
    _parse_utc(at, "at")
    if not isinstance(reference, str):
        raise HospitalError("reference must be text")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        medicine = conn.execute(
            "SELECT quantity,active FROM medicines WHERE id=?", (medicine_id,)
        ).fetchone()
        if medicine is None or not medicine["active"]:
            raise HospitalError("active medicine not found")
        existing = conn.execute(
            "SELECT id FROM medicine_batches WHERE medicine_id=? AND batch_no=?",
            (medicine_id, batch_no.strip()),
        ).fetchone()
        if existing is not None:
            raise HospitalError("batch number already exists for this medicine")
        new_balance = int(medicine["quantity"]) + quantity
        cur = conn.execute(
            """INSERT INTO medicine_batches
               (medicine_id,batch_no,expiry_date,quantity,received_at,reference,created_by)
               VALUES(?,?,?,?,?,?,?)""",
            (medicine_id, batch_no.strip(), expiry_date, quantity, at, reference.strip(), actor),
        )
        batch_id = int(cur.lastrowid)
        conn.execute("UPDATE medicines SET quantity=? WHERE id=?", (new_balance, medicine_id))
        conn.execute(
            """INSERT INTO medicine_stock_movements
               (medicine_id,movement_type,quantity_delta,balance_after,reference,actor,occurred_at)
               VALUES(?,?,?,?,?,?,?)""",
            (medicine_id, "restock", quantity, new_balance,
             f"batch:{batch_no.strip()}" + (f" | {reference.strip()}" if reference.strip() else ""),
             actor, at),
        )
        conn.execute(
            """INSERT INTO medicine_batch_movements
               (batch_id,medicine_id,movement_type,quantity_delta,balance_after,
                reference,actor,occurred_at)
               VALUES(?,?,'restock',?,?,?,?,?)""",
            (batch_id, medicine_id, quantity, quantity, reference.strip(), actor, at),
        )
        db._audit(conn, actor, "medicine.batch_received", "medicine_batch", batch_id, at,
                  {"medicine_id": medicine_id, "batch_no": batch_no.strip(),
                   "quantity": quantity, "expiry_date": expiry_date,
                   "balance_after": new_balance})
        conn.commit()
        return {"batch_id": batch_id, "batch_no": batch_no.strip(),
                "quantity": quantity, "medicine_balance": new_balance, "expiry_date": expiry_date}


def prescribe_medicine(db: HospitalDB, *, patient_id: int, medicine_id: int, dose: str,
                       frequency: str, duration: str, quantity: int, prescribed_at: str,
                       actor: str, encounter_id: int | None = None) -> int:
    if not all(isinstance(value, str) and value.strip() for value in (dose, frequency, duration)):
        raise HospitalError("dose, frequency and duration are required")
    if type(quantity) is not int or quantity <= 0:
        raise HospitalError("prescription quantity must be a positive integer")
    _parse_utc(prescribed_at, "prescribed_at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        patient = conn.execute("SELECT active FROM patients WHERE id=?", (patient_id,)).fetchone()
        medicine = conn.execute("SELECT active FROM medicines WHERE id=?", (medicine_id,)).fetchone()
        if patient is None or not patient["active"]:
            raise HospitalError("active patient not found")
        if medicine is None or not medicine["active"]:
            raise HospitalError("active medicine not found")
        if encounter_id is not None:
            encounter = conn.execute("SELECT patient_id,status FROM encounters WHERE id=?",
                                     (encounter_id,)).fetchone()
            if encounter is None or encounter["patient_id"] != patient_id or encounter["status"] != "open":
                raise HospitalError("an open encounter for this patient is required")
        cur = conn.execute("""INSERT INTO prescriptions
            (patient_id,encounter_id,medicine_id,dose,frequency,duration,quantity,prescribed_at)
            VALUES(?,?,?,?,?,?,?,?)""",
            (patient_id, encounter_id, medicine_id, dose.strip(), frequency.strip(),
             duration.strip(), quantity, prescribed_at))
        ident = cur.lastrowid
        db._audit(conn, actor, "prescription.created", "prescription", ident, prescribed_at,
                  {"medicine_id": medicine_id, "quantity": quantity})
        conn.commit()
        return int(ident)


def dispense_prescription(db: HospitalDB, *, prescription_id: int,
                          dispensed_at: str, actor: str) -> None:
    """Atomically deduct stock and mark a prescription dispensed exactly once."""
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("""SELECT p.status,p.quantity,p.medicine_id,p.prescribed_at,
            m.quantity AS stock,m.active FROM prescriptions p
            JOIN medicines m ON m.id=p.medicine_id WHERE p.id=?""",
            (prescription_id,)).fetchone()
        if row is None or row["status"] != "pending":
            raise HospitalError("pending prescription not found")
        if _parse_utc(dispensed_at, "dispensed_at") < _parse_utc(row["prescribed_at"], "prescribed_at"):
            raise HospitalError("dispense timestamp cannot precede the prescription")
        if not row["active"]:
            raise HospitalError("medicine is inactive")
        if row["stock"] < row["quantity"]:
            raise HospitalError("insufficient medicine stock")
        # Dispense using FEFO (first-expiring, first-out); expired stock is never dispensed.
        dispense_day = _parse_utc(dispensed_at, "dispensed_at").date().isoformat()
        lots = conn.execute(
            """SELECT id,quantity,expiry_date FROM medicine_batches
               WHERE medicine_id=? AND quantity>0
                 AND (expiry_date IS NULL OR expiry_date>=?)
               ORDER BY CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END,
                        expiry_date ASC, id ASC""",
            (row["medicine_id"], dispense_day),
        ).fetchall()
        available = sum(int(lot["quantity"]) for lot in lots)
        if available < int(row["quantity"]):
            raise HospitalError("insufficient non-expired batch stock; check expiry dates and batch balances")
        remaining = int(row["quantity"])
        for lot in lots:
            if remaining <= 0:
                break
            take = min(remaining, int(lot["quantity"]))
            balance = int(lot["quantity"]) - take
            conn.execute("UPDATE medicine_batches SET quantity=? WHERE id=?", (balance, lot["id"]))
            conn.execute(
                """INSERT INTO medicine_batch_movements
                   (batch_id,medicine_id,movement_type,quantity_delta,balance_after,
                    prescription_id,reference,actor,occurred_at)
                   VALUES(?,?, 'dispense', ?,?,?,?,?,?)""",
                (lot["id"], row["medicine_id"], -take, balance, prescription_id,
                 f"prescription:{prescription_id}", actor, dispensed_at),
            )
            remaining -= take
        new_balance = int(row["stock"] - row["quantity"])
        conn.execute("UPDATE medicines SET quantity=? WHERE id=?",
                     (new_balance, row["medicine_id"]))
        conn.execute("UPDATE prescriptions SET status='dispensed' WHERE id=?",
                     (prescription_id,))
        conn.execute(
            """INSERT INTO medicine_stock_movements
               (medicine_id,movement_type,quantity_delta,balance_after,source_key,
                reference,prescription_id,actor,occurred_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (row["medicine_id"], "dispense", -int(row["quantity"]), new_balance,
             f"prescription:dispense:{prescription_id}", f"prescription:{prescription_id}",
             prescription_id, actor, dispensed_at),
        )
        db._audit(conn, actor, "prescription.dispensed", "prescription", prescription_id,
                  dispensed_at, {"medicine_id": row["medicine_id"], "quantity": row["quantity"],
                                 "balance_after": new_balance})
        conn.commit()


def create_bed(db: HospitalDB, *, ward: str, bed_no: str, actor: str, at: str) -> int:
    if not ward.strip() or not bed_no.strip():
        raise HospitalError("ward and bed number are required")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute("INSERT INTO beds(ward,bed_no) VALUES(?,?)",
                           (ward.strip(), bed_no.strip()))
        ident = cur.lastrowid
        db._audit(conn, actor, "bed.created", "bed", ident, at,
                  {"ward": ward.strip(), "bed_no": bed_no.strip()})
        conn.commit()
        return int(ident)
