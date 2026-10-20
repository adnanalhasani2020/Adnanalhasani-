"""Minimal, self-contained hospital operations core (SQLite, standard library only).

This is a foundation for development/testing, not a certified clinical system.
All timestamps are ISO-8601 strings supplied by the caller.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK(role IN ('admin','doctor','nurse','reception','lab','pharmacist','accountant')),
  active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))
);
CREATE TABLE IF NOT EXISTS patients (
  id INTEGER PRIMARY KEY, patient_no TEXT NOT NULL UNIQUE,
  full_name TEXT NOT NULL, date_of_birth TEXT, sex TEXT,
  phone TEXT, address TEXT, emergency_contact TEXT,
  allergies TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
  active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS doctors (
  id INTEGER PRIMARY KEY, employee_no TEXT NOT NULL UNIQUE,
  full_name TEXT NOT NULL, specialty TEXT NOT NULL,
  phone TEXT, active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))
);
CREATE TABLE IF NOT EXISTS appointments (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  doctor_id INTEGER NOT NULL REFERENCES doctors(id),
  starts_at TEXT NOT NULL, ends_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'scheduled'
    CHECK(status IN ('scheduled','checked_in','completed','cancelled','no_show')),
  reason TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL,
  CHECK(ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS appointments_doctor_time ON appointments(doctor_id, starts_at, ends_at);
CREATE INDEX IF NOT EXISTS appointments_patient_time ON appointments(patient_id, starts_at);
-- Enforce schedule integrity even when a caller bypasses the Python workflow.
CREATE TRIGGER IF NOT EXISTS appointments_no_overlap_insert
BEFORE INSERT ON appointments
WHEN NEW.status IN ('scheduled','checked_in')
 AND EXISTS (
   SELECT 1 FROM appointments a
   WHERE a.doctor_id=NEW.doctor_id
     AND a.status IN ('scheduled','checked_in')
     AND a.starts_at < NEW.ends_at AND a.ends_at > NEW.starts_at
 )
BEGIN
  SELECT RAISE(ABORT, 'doctor already has an overlapping appointment');
END;
CREATE TRIGGER IF NOT EXISTS appointments_no_overlap_update
BEFORE UPDATE OF doctor_id, starts_at, ends_at, status ON appointments
WHEN NEW.status IN ('scheduled','checked_in')
 AND EXISTS (
   SELECT 1 FROM appointments a
   WHERE a.id <> NEW.id
     AND a.doctor_id=NEW.doctor_id
     AND a.status IN ('scheduled','checked_in')
     AND a.starts_at < NEW.ends_at AND a.ends_at > NEW.starts_at
 )
BEGIN
  SELECT RAISE(ABORT, 'doctor already has an overlapping appointment');
END;
-- A patient cannot attend two active appointments at the same time, even with different doctors.
CREATE TRIGGER IF NOT EXISTS appointments_patient_no_overlap_insert
BEFORE INSERT ON appointments
WHEN NEW.status IN ('scheduled','checked_in')
 AND EXISTS (
   SELECT 1 FROM appointments a
   WHERE a.patient_id=NEW.patient_id
     AND a.status IN ('scheduled','checked_in')
     AND a.starts_at < NEW.ends_at AND a.ends_at > NEW.starts_at
 )
BEGIN
  SELECT RAISE(ABORT, 'patient already has an overlapping appointment');
END;
CREATE TRIGGER IF NOT EXISTS appointments_patient_no_overlap_update
BEFORE UPDATE OF patient_id, starts_at, ends_at, status ON appointments
WHEN NEW.status IN ('scheduled','checked_in')
 AND EXISTS (
   SELECT 1 FROM appointments a
   WHERE a.id <> NEW.id
     AND a.patient_id=NEW.patient_id
     AND a.status IN ('scheduled','checked_in')
     AND a.starts_at < NEW.ends_at AND a.ends_at > NEW.starts_at
 )
BEGIN
  SELECT RAISE(ABORT, 'patient already has an overlapping appointment');
END;
CREATE TABLE IF NOT EXISTS encounters (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  doctor_id INTEGER REFERENCES doctors(id), appointment_id INTEGER REFERENCES appointments(id),
  opened_at TEXT NOT NULL, closed_at TEXT, chief_complaint TEXT NOT NULL DEFAULT '',
  diagnosis TEXT NOT NULL DEFAULT '', clinical_notes TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','closed'))
);
-- Keep encounter lifecycle state and closure timestamp consistent for all writers.
CREATE TRIGGER IF NOT EXISTS encounters_state_consistent_insert
BEFORE INSERT ON encounters
WHEN (NEW.status='open' AND NEW.closed_at IS NOT NULL)
  OR (NEW.status='closed' AND NEW.closed_at IS NULL)
BEGIN
  SELECT RAISE(ABORT, 'encounter status and closure timestamp are inconsistent');
END;
CREATE TRIGGER IF NOT EXISTS encounters_state_consistent_update
BEFORE UPDATE OF status, closed_at ON encounters
WHEN (NEW.status='open' AND NEW.closed_at IS NOT NULL)
  OR (NEW.status='closed' AND NEW.closed_at IS NULL)
BEGIN
  SELECT RAISE(ABORT, 'encounter status and closure timestamp are inconsistent');
END;
CREATE TABLE IF NOT EXISTS beds (
  id INTEGER PRIMARY KEY, ward TEXT NOT NULL, bed_no TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
  UNIQUE(ward, bed_no)
);
CREATE TABLE IF NOT EXISTS admissions (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  bed_id INTEGER NOT NULL REFERENCES beds(id), admitted_at TEXT NOT NULL,
  discharged_at TEXT, reason TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'admitted' CHECK(status IN ('admitted','discharged')),
  CHECK(discharged_at IS NULL OR discharged_at >= admitted_at)
);
CREATE INDEX IF NOT EXISTS admissions_active_bed ON admissions(bed_id, status);
-- A bed may have at most one active admission, including direct SQL writes.
CREATE TRIGGER IF NOT EXISTS admissions_one_active_bed_insert
BEFORE INSERT ON admissions
WHEN NEW.status='admitted'
 AND EXISTS (SELECT 1 FROM admissions a WHERE a.bed_id=NEW.bed_id AND a.status='admitted')
BEGIN
  SELECT RAISE(ABORT, 'bed is already occupied');
END;
CREATE TRIGGER IF NOT EXISTS admissions_one_active_bed_update
BEFORE UPDATE OF bed_id, status ON admissions
WHEN NEW.status='admitted'
 AND EXISTS (
   SELECT 1 FROM admissions a
   WHERE a.id <> NEW.id AND a.bed_id=NEW.bed_id AND a.status='admitted'
 )
BEGIN
  SELECT RAISE(ABORT, 'bed is already occupied');
END;
-- A patient may have only one active admission, even when another bed is available.
CREATE TRIGGER IF NOT EXISTS admissions_one_active_patient_insert
BEFORE INSERT ON admissions
WHEN NEW.status='admitted'
 AND EXISTS (SELECT 1 FROM admissions a WHERE a.patient_id=NEW.patient_id AND a.status='admitted')
BEGIN
  SELECT RAISE(ABORT, 'patient already has an active admission');
END;
CREATE TRIGGER IF NOT EXISTS admissions_one_active_patient_update
BEFORE UPDATE OF patient_id, status ON admissions
WHEN NEW.status='admitted'
 AND EXISTS (
   SELECT 1 FROM admissions a
   WHERE a.id <> NEW.id AND a.patient_id=NEW.patient_id AND a.status='admitted'
 )
BEGIN
  SELECT RAISE(ABORT, 'patient already has an active admission');
END;
-- Keep admission status and discharge timestamp consistent for all writers.
CREATE TRIGGER IF NOT EXISTS admissions_state_consistent_insert
BEFORE INSERT ON admissions
WHEN (NEW.status='admitted' AND NEW.discharged_at IS NOT NULL)
  OR (NEW.status='discharged' AND NEW.discharged_at IS NULL)
BEGIN
  SELECT RAISE(ABORT, 'admission status and discharge timestamp are inconsistent');
END;
CREATE TRIGGER IF NOT EXISTS admissions_state_consistent_update
BEFORE UPDATE OF status, discharged_at ON admissions
WHEN (NEW.status='admitted' AND NEW.discharged_at IS NOT NULL)
  OR (NEW.status='discharged' AND NEW.discharged_at IS NULL)
BEGIN
  SELECT RAISE(ABORT, 'admission status and discharge timestamp are inconsistent');
END;
CREATE TABLE IF NOT EXISTS lab_orders (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  encounter_id INTEGER REFERENCES encounters(id), ordered_by INTEGER REFERENCES doctors(id),
  test_name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ordered'
    CHECK(status IN ('ordered','collected','processing','resulted','cancelled')),
  result TEXT, ordered_at TEXT NOT NULL, resulted_at TEXT
);
CREATE TABLE IF NOT EXISTS lab_order_events (
  id INTEGER PRIMARY KEY,
  lab_order_id INTEGER NOT NULL REFERENCES lab_orders(id),
  status TEXT NOT NULL CHECK(status IN ('ordered','collected','processing','resulted','cancelled')),
  occurred_at TEXT NOT NULL,
  actor TEXT NOT NULL,
  UNIQUE(lab_order_id, status, occurred_at)
);
CREATE INDEX IF NOT EXISTS lab_order_events_timeline
  ON lab_order_events(lab_order_id, id);
CREATE TABLE IF NOT EXISTS medicines (
  id INTEGER PRIMARY KEY, sku TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 0 CHECK(quantity >= 0),
  reorder_level INTEGER NOT NULL DEFAULT 0 CHECK(reorder_level >= 0),
  unit TEXT NOT NULL DEFAULT 'unit', active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))
);
CREATE TABLE IF NOT EXISTS prescriptions (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  encounter_id INTEGER REFERENCES encounters(id), medicine_id INTEGER NOT NULL REFERENCES medicines(id),
  dose TEXT NOT NULL, frequency TEXT NOT NULL, duration TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity > 0), status TEXT NOT NULL DEFAULT 'pending'
    CHECK(status IN ('pending','dispensed','cancelled')), prescribed_at TEXT NOT NULL
);
-- Append-only stock ledger: quantity snapshots remain for fast reads, but every
-- inventory change is represented by a durable, attributable movement.
CREATE TABLE IF NOT EXISTS medicine_stock_movements (
  id INTEGER PRIMARY KEY,
  medicine_id INTEGER NOT NULL REFERENCES medicines(id),
  movement_type TEXT NOT NULL CHECK(movement_type IN
    ('opening_balance','initial_stock','restock','dispense')),
  quantity_delta INTEGER NOT NULL,
  balance_after INTEGER NOT NULL CHECK(balance_after >= 0),
  source_key TEXT UNIQUE,
  reference TEXT NOT NULL DEFAULT '',
  prescription_id INTEGER REFERENCES prescriptions(id),
  actor TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  CHECK(
    (movement_type IN ('opening_balance','initial_stock') AND quantity_delta >= 0) OR
    (movement_type='restock' AND quantity_delta > 0) OR
    (movement_type='dispense' AND quantity_delta < 0)
  ),
  CHECK((movement_type='dispense' AND prescription_id IS NOT NULL) OR
        (movement_type<>'dispense' AND prescription_id IS NULL))
);
CREATE INDEX IF NOT EXISTS medicine_stock_movements_timeline
  ON medicine_stock_movements(medicine_id, id);
CREATE INDEX IF NOT EXISTS medicine_stock_movements_type_time
  ON medicine_stock_movements(movement_type, occurred_at);
CREATE TRIGGER IF NOT EXISTS medicine_stock_movements_no_update
BEFORE UPDATE ON medicine_stock_movements
BEGIN
  SELECT RAISE(ABORT, 'medicine stock movements are append-only');
END;
CREATE TRIGGER IF NOT EXISTS medicine_stock_movements_no_delete
BEFORE DELETE ON medicine_stock_movements
BEGIN
  SELECT RAISE(ABORT, 'medicine stock movements are append-only');
END;

CREATE TABLE IF NOT EXISTS invoices (
  id INTEGER PRIMARY KEY, invoice_no TEXT NOT NULL UNIQUE,
  patient_id INTEGER NOT NULL REFERENCES patients(id),
  encounter_id INTEGER REFERENCES encounters(id), total_minor INTEGER NOT NULL CHECK(total_minor >= 0),
  currency TEXT NOT NULL DEFAULT 'YER',
  status TEXT NOT NULL DEFAULT 'unpaid' CHECK(status IN ('unpaid','partial','paid','void')),
  created_at TEXT NOT NULL,
  idempotency_key TEXT,
  request_sha256 TEXT
);
-- Enforce invoice/encounter patient consistency for writers that bypass the application.
CREATE TRIGGER IF NOT EXISTS invoice_encounter_patient_match_insert
BEFORE INSERT ON invoices
WHEN NEW.encounter_id IS NOT NULL
 AND NOT EXISTS (
   SELECT 1 FROM encounters e
   WHERE e.id=NEW.encounter_id AND e.patient_id=NEW.patient_id
 )
BEGIN
  SELECT RAISE(ABORT, 'invoice encounter must belong to the same patient');
END;
CREATE TRIGGER IF NOT EXISTS invoice_encounter_patient_match_update
BEFORE UPDATE OF patient_id, encounter_id ON invoices
WHEN NEW.encounter_id IS NOT NULL
 AND NOT EXISTS (
   SELECT 1 FROM encounters e
   WHERE e.id=NEW.encounter_id AND e.patient_id=NEW.patient_id
 )
BEGIN
  SELECT RAISE(ABORT, 'invoice encounter must belong to the same patient');
END;
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY, invoice_id INTEGER NOT NULL REFERENCES invoices(id),
  amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), method TEXT NOT NULL,
  reference TEXT, received_at TEXT NOT NULL,
  idempotency_key TEXT,
  request_sha256 TEXT
);
-- Transactional handoff intents; consumers must not treat these as Finance recognition.
CREATE TABLE IF NOT EXISTS finance_outbox (
  id INTEGER PRIMARY KEY,
  event_key TEXT NOT NULL UNIQUE,
  event_type TEXT NOT NULL CHECK(event_type IN ('hospital.invoice.created','hospital.payment.received')),
  aggregate_type TEXT NOT NULL CHECK(aggregate_type IN ('invoice','payment')),
  aggregate_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','delivered','failed')),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  delivered_at TEXT,
  last_error TEXT
);
CREATE INDEX IF NOT EXISTS finance_outbox_pending
  ON finance_outbox(status, id);
CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY, actor TEXT NOT NULL, action TEXT NOT NULL,
  entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
  details_json TEXT NOT NULL DEFAULT '{}', occurred_at TEXT NOT NULL
);
-- Audit history is append-only: corrections must be represented by new events.
CREATE TRIGGER IF NOT EXISTS hospital_audit_log_no_update
BEFORE UPDATE ON audit_log
BEGIN
  SELECT RAISE(ABORT, 'audit_log is append-only');
END;
CREATE TRIGGER IF NOT EXISTS hospital_audit_log_no_delete
BEFORE DELETE ON audit_log
BEGIN
  SELECT RAISE(ABORT, 'audit_log is append-only');
END;
-- Manager settings remain proposals until an authorized reviewer approves them.
CREATE TABLE IF NOT EXISTS manager_setting_proposals (
  id INTEGER PRIMARY KEY,
  activity_profile TEXT NOT NULL CHECK(activity_profile IN ('general','outpatient','specialty','admin')),
  modules_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'proposed' CHECK(status IN ('proposed','approved','rejected')),
  version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL,
  reviewed_by TEXT,
  reviewed_at TEXT,
  review_note TEXT,
  CHECK((status='proposed' AND reviewed_by IS NULL AND reviewed_at IS NULL) OR
        (status IN ('approved','rejected') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS manager_setting_proposals_status_id
  ON manager_setting_proposals(status, id DESC);

-- The last approved operational display profile is effective until another proposal
-- is approved. This controls module presentation only, never role permissions.
CREATE TABLE IF NOT EXISTS manager_active_settings (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  activity_profile TEXT NOT NULL CHECK(activity_profile IN ('general','outpatient','specialty','admin')),
  modules_json TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(version >= 1),
  source_proposal_id INTEGER NOT NULL REFERENCES manager_setting_proposals(id),
  activated_by TEXT NOT NULL,
  activated_at TEXT NOT NULL
);

CREATE TRIGGER IF NOT EXISTS manager_setting_proposals_no_delete
BEFORE DELETE ON manager_setting_proposals
BEGIN
  SELECT RAISE(ABORT, 'manager setting proposals are retained for audit');
END;

-- Human-selected partial-settlement preference is recorded separately from activation.
-- Approval documents the manager's choice; Finance recognition remains blocked until
-- the corresponding obligation/remaining-balance model is implemented and enabled.
CREATE TABLE IF NOT EXISTS manager_finance_policy_decisions (
  id INTEGER PRIMARY KEY,
  policy_key TEXT NOT NULL CHECK(policy_key IN ('multiple_allocations','remaining_balance')),
  rationale TEXT NOT NULL CHECK(length(trim(rationale)) BETWEEN 1 AND 500),
  status TEXT NOT NULL DEFAULT 'proposed' CHECK(status IN ('proposed','approved','rejected')),
  version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL,
  reviewed_by TEXT,
  reviewed_at TEXT,
  review_note TEXT,
  CHECK((status='proposed' AND reviewed_by IS NULL AND reviewed_at IS NULL) OR
        (status IN ('approved','rejected') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS manager_finance_policy_decisions_status_id
  ON manager_finance_policy_decisions(status, id DESC);
CREATE TRIGGER IF NOT EXISTS manager_finance_policy_decisions_no_delete
BEFORE DELETE ON manager_finance_policy_decisions
BEGIN
  SELECT RAISE(ABORT, 'finance policy decisions are retained for audit');
END;

"""


class HospitalError(ValueError):
    """A validated business-rule or input error."""


class HospitalDB:
    def __init__(self, path: str | Path = "hospital.sqlite3"):
        self.path = str(path)
        with self.connect() as db:
            db.executescript(SCHEMA)
            # Preserve the current stock as an opening ledger balance for legacy databases.
            # The NOT EXISTS guard makes this migration idempotent across restarts.
            db.execute("""
                INSERT INTO medicine_stock_movements
                    (medicine_id,movement_type,quantity_delta,balance_after,source_key,
                     reference,actor,occurred_at)
                SELECT m.id,'opening_balance',m.quantity,m.quantity,
                       'migration:opening:' || m.id,'legacy stock at ledger activation',
                       'system:migration',CURRENT_TIMESTAMP
                FROM medicines m
                WHERE NOT EXISTS (
                    SELECT 1 FROM medicine_stock_movements sm WHERE sm.medicine_id=m.id
                )
            """)
            # Upgrade existing Hospital databases without rewriting historical rows.
            invoice_columns = {row["name"] for row in db.execute("PRAGMA table_info(invoices)")}
            if "idempotency_key" not in invoice_columns:
                db.execute("ALTER TABLE invoices ADD COLUMN idempotency_key TEXT")
            if "request_sha256" not in invoice_columns:
                db.execute("ALTER TABLE invoices ADD COLUMN request_sha256 TEXT")
            db.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS invoices_idempotency_key_unique "
                "ON invoices(idempotency_key) WHERE idempotency_key IS NOT NULL"
            )
            payment_columns = {row["name"] for row in db.execute("PRAGMA table_info(payments)")}
            if "idempotency_key" not in payment_columns:
                db.execute("ALTER TABLE payments ADD COLUMN idempotency_key TEXT")
            if "request_sha256" not in payment_columns:
                db.execute("ALTER TABLE payments ADD COLUMN request_sha256 TEXT")
            db.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS payments_idempotency_key_unique "
                "ON payments(idempotency_key) WHERE idempotency_key IS NOT NULL"
            )

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        try:
            yield db
        finally:
            db.close()

    @staticmethod
    def _audit(db: sqlite3.Connection, actor: str, action: str,
               entity_type: str, entity_id: Any, at: str, details: dict | None = None) -> None:
        db.execute(
            "INSERT INTO audit_log(actor,action,entity_type,entity_id,details_json,occurred_at) VALUES(?,?,?,?,?,?)",
            (actor, action, entity_type, str(entity_id),
             json.dumps(details or {}, ensure_ascii=False, sort_keys=True), at),
        )

    def register_patient(self, *, patient_no: str, full_name: str, created_at: str,
                         actor: str, date_of_birth: str | None = None, sex: str | None = None,
                         phone: str | None = None, address: str | None = None,
                         emergency_contact: str | None = None, allergies: str = "",
                         notes: str = "") -> int:
        if not patient_no.strip() or not full_name.strip() or not created_at.strip():
            raise HospitalError("patient_no, full_name and created_at are required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cur = db.execute("""INSERT INTO patients
                (patient_no,full_name,date_of_birth,sex,phone,address,emergency_contact,allergies,notes,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (patient_no.strip(), full_name.strip(), date_of_birth, sex, phone, address,
                 emergency_contact, allergies, notes, created_at))
            pid = cur.lastrowid
            self._audit(db, actor, "patient.registered", "patient", pid, created_at,
                        {"patient_no": patient_no.strip()})
            db.commit()
            return int(pid)

    def register_doctor(self, *, employee_no: str, full_name: str, specialty: str,
                        actor: str, phone: str | None = None, at: str) -> int:
        if not employee_no.strip() or not full_name.strip() or not specialty.strip():
            raise HospitalError("employee_no, full_name and specialty are required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cur = db.execute("INSERT INTO doctors(employee_no,full_name,specialty,phone) VALUES(?,?,?,?)",
                             (employee_no.strip(), full_name.strip(), specialty.strip(), phone))
            did = cur.lastrowid
            self._audit(db, actor, "doctor.registered", "doctor", did, at)
            db.commit()
            return int(did)

    def book_appointment(self, *, patient_id: int, doctor_id: int, starts_at: str,
                         ends_at: str, created_at: str, actor: str, reason: str = "") -> int:
        if not starts_at or not ends_at or ends_at <= starts_at:
            raise HospitalError("ends_at must be later than starts_at (ISO-8601 sortable values)")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for table, key, ident in (("patients", "id", patient_id), ("doctors", "id", doctor_id)):
                row = db.execute(f"SELECT active FROM {table} WHERE {key}=?", (ident,)).fetchone()
                if row is None or not row["active"]:
                    raise HospitalError(f"active {table[:-1]} not found")
            conflict = db.execute("""SELECT 1 FROM appointments
                WHERE doctor_id=? AND status IN ('scheduled','checked_in')
                AND starts_at < ? AND ends_at > ? LIMIT 1""",
                (doctor_id, ends_at, starts_at)).fetchone()
            if conflict:
                raise HospitalError("doctor already has an overlapping appointment")
            patient_conflict = db.execute("""SELECT 1 FROM appointments
                WHERE patient_id=? AND status IN ('scheduled','checked_in')
                AND starts_at < ? AND ends_at > ? LIMIT 1""",
                (patient_id, ends_at, starts_at)).fetchone()
            if patient_conflict:
                raise HospitalError("patient already has an overlapping appointment")
            cur = db.execute("""INSERT INTO appointments
                (patient_id,doctor_id,starts_at,ends_at,reason,created_at)
                VALUES(?,?,?,?,?,?)""",
                (patient_id, doctor_id, starts_at, ends_at, reason, created_at))
            aid = cur.lastrowid
            self._audit(db, actor, "appointment.booked", "appointment", aid, created_at)
            db.commit()
            return int(aid)

    @staticmethod
    def _parse_appointment_action_time(value: str, label: str) -> datetime:
        if not isinstance(value, str) or not value.strip():
            raise HospitalError(f"{label} timestamp is required")
        try:
            return datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        except ValueError as exc:
            raise HospitalError(f"{label} timestamp must be valid ISO-8601") from exc

    def transition_appointment(self, *, appointment_id: int, target_status: str,
                               at: str, actor: str) -> None:
        """Apply the supported administrative terminal transitions atomically."""
        if target_status not in {"cancelled", "no_show"}:
            raise HospitalError("unsupported appointment terminal status")
        action_time = self._parse_appointment_action_time(at, "Appointment action")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT status, starts_at FROM appointments WHERE id=?", (appointment_id,)
            ).fetchone()
            if row is None:
                raise HospitalError("appointment not found")
            if row["status"] != "scheduled":
                raise HospitalError("only scheduled appointments can be cancelled or marked no-show")
            if target_status == "no_show":
                starts_at = self._parse_appointment_action_time(
                    row["starts_at"], "Appointment start"
                )
                action_aware = action_time.tzinfo is not None and action_time.utcoffset() is not None
                start_aware = starts_at.tzinfo is not None and starts_at.utcoffset() is not None
                if action_aware != start_aware:
                    raise HospitalError("appointment action and start timestamps must use matching timezone awareness")
                effective_action = action_time.astimezone(timezone.utc) if action_aware else action_time
                effective_start = starts_at.astimezone(timezone.utc) if start_aware else starts_at
                if effective_action < effective_start:
                    raise HospitalError("no-show cannot be recorded before appointment start")
            changed = db.execute(
                "UPDATE appointments SET status=? WHERE id=? AND status='scheduled'",
                (target_status, appointment_id),
            ).rowcount
            if changed != 1:
                raise HospitalError("appointment state changed; retry with current state")
            self._audit(
                db, actor, f"appointment.{target_status}", "appointment", appointment_id, at,
                {"previous_status": "scheduled", "status": target_status},
            )
            db.commit()

    def admit_patient(self, *, patient_id: int, bed_id: int, admitted_at: str,
                      actor: str, reason: str = "") -> int:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            patient = db.execute("SELECT active FROM patients WHERE id=?", (patient_id,)).fetchone()
            bed = db.execute("SELECT active FROM beds WHERE id=?", (bed_id,)).fetchone()
            occupied = db.execute("SELECT 1 FROM admissions WHERE bed_id=? AND status='admitted'",
                                  (bed_id,)).fetchone()
            patient_admitted = db.execute(
                "SELECT 1 FROM admissions WHERE patient_id=? AND status='admitted' LIMIT 1",
                (patient_id,),
            ).fetchone()
            if patient is None or not patient["active"]:
                raise HospitalError("active patient not found")
            if bed is None or not bed["active"]:
                raise HospitalError("active bed not found")
            if occupied:
                raise HospitalError("bed is already occupied")
            if patient_admitted:
                raise HospitalError("patient already has an active admission")
            cur = db.execute("""INSERT INTO admissions(patient_id,bed_id,admitted_at,reason)
                VALUES(?,?,?,?)""", (patient_id, bed_id, admitted_at, reason))
            ident = cur.lastrowid
            self._audit(db, actor, "patient.admitted", "admission", ident, admitted_at, {"bed_id": bed_id})
            db.commit()
            return int(ident)

    def discharge_patient(self, *, admission_id: int, discharged_at: str, actor: str) -> None:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT admitted_at,status FROM admissions WHERE id=?", (admission_id,)).fetchone()
            if row is None or row["status"] != "admitted":
                raise HospitalError("active admission not found")
            if discharged_at < row["admitted_at"]:
                raise HospitalError("discharge cannot precede admission")
            db.execute("UPDATE admissions SET discharged_at=?,status='discharged' WHERE id=?",
                       (discharged_at, admission_id))
            self._audit(db, actor, "patient.discharged", "admission", admission_id, discharged_at)
            db.commit()

    def create_invoice(self, *, invoice_no: str, patient_id: int, total_minor: int,
                       created_at: str, actor: str, currency: str = "YER",
                       encounter_id: int | None = None,
                       idempotency_key: str | None = None) -> int:
        if type(total_minor) is not int or total_minor < 0:
            raise HospitalError("invoice total must be a non-negative integer in minor units")
        if not isinstance(invoice_no, str) or not invoice_no.strip():
            raise HospitalError("invoice number is required")
        if idempotency_key is not None:
            if not isinstance(idempotency_key, str) or not idempotency_key.strip() or len(idempotency_key) > 200:
                raise HospitalError("idempotency_key must be a non-empty string of at most 200 characters")
            idempotency_key = idempotency_key.strip()
            request = {"invoice_no": invoice_no.strip(), "patient_id": patient_id,
                       "encounter_id": encounter_id, "total_minor": total_minor,
                       "currency": currency, "created_at": created_at}
            request_sha256 = hashlib.sha256(
                json.dumps(request, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            ).hexdigest()
        else:
            request_sha256 = None
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if idempotency_key is not None:
                existing = db.execute(
                    "SELECT id,request_sha256 FROM invoices WHERE idempotency_key=?",
                    (idempotency_key,),
                ).fetchone()
                if existing is not None:
                    if existing["request_sha256"] != request_sha256:
                        raise HospitalError("idempotency_key was already used with different invoice details")
                    db.commit()
                    return int(existing["id"])
            if encounter_id is not None:
                encounter = db.execute(
                    "SELECT patient_id FROM encounters WHERE id=?", (encounter_id,)
                ).fetchone()
                if encounter is None:
                    raise HospitalError("encounter not found")
                if encounter["patient_id"] != patient_id:
                    raise HospitalError("encounter does not belong to this patient")
            cur = db.execute("""INSERT INTO invoices
                (invoice_no,patient_id,encounter_id,total_minor,currency,created_at,idempotency_key,request_sha256)
                VALUES(?,?,?,?,?,?,?,?)""",
                (invoice_no.strip(), patient_id, encounter_id, total_minor, currency, created_at,
                 idempotency_key, request_sha256))
            ident = cur.lastrowid
            self._audit(db, actor, "invoice.created", "invoice", ident, created_at,
                        {"total_minor": total_minor, "currency": currency})
            db.execute(
                """INSERT INTO finance_outbox
                   (event_key,event_type,aggregate_type,aggregate_id,payload_json,created_at,updated_at)
                   VALUES(?,?,?,?,?,?,?)""",
                (f"hospital:invoice:{ident}:created:v1", "hospital.invoice.created", "invoice",
                 str(ident), json.dumps({"invoice_id": ident, "invoice_no": invoice_no.strip(),
                 "total_minor": total_minor, "currency": currency, "created_at": created_at},
                 ensure_ascii=False, sort_keys=True), created_at, created_at),
            )
            db.commit()
            return int(ident)

    def record_payment(self, *, invoice_id: int, amount_minor: int, method: str,
                       received_at: str, actor: str, reference: str | None = None,
                       idempotency_key: str | None = None) -> int:
        if type(amount_minor) is not int or amount_minor <= 0:
            raise HospitalError("payment amount must be a positive integer in minor units")
        if not isinstance(method, str) or not method.strip():
            raise HospitalError("payment method is required")
        if idempotency_key is not None:
            if not isinstance(idempotency_key, str) or not idempotency_key.strip() or len(idempotency_key) > 200:
                raise HospitalError("idempotency_key must be a non-empty string of at most 200 characters")
            idempotency_key = idempotency_key.strip()
            request = {
                "invoice_id": invoice_id, "amount_minor": amount_minor,
                "method": method.strip(), "reference": reference, "received_at": received_at,
            }
            request_sha256 = hashlib.sha256(
                json.dumps(request, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            ).hexdigest()
        else:
            request_sha256 = None
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if idempotency_key is not None:
                existing = db.execute(
                    "SELECT id,request_sha256 FROM payments WHERE idempotency_key=?",
                    (idempotency_key,),
                ).fetchone()
                if existing is not None:
                    if existing["request_sha256"] != request_sha256:
                        raise HospitalError("idempotency_key was already used with different payment details")
                    db.commit()
                    return int(existing["id"])
            invoice = db.execute("SELECT total_minor,status FROM invoices WHERE id=?", (invoice_id,)).fetchone()
            if invoice is None or invoice["status"] == "void":
                raise HospitalError("valid invoice not found")
            paid = db.execute("SELECT COALESCE(SUM(amount_minor),0) AS n FROM payments WHERE invoice_id=?",
                              (invoice_id,)).fetchone()["n"]
            if paid + amount_minor > invoice["total_minor"]:
                raise HospitalError("payment exceeds invoice balance")
            cur = db.execute(
                """INSERT INTO payments(invoice_id,amount_minor,method,reference,received_at,idempotency_key,request_sha256)
                   VALUES(?,?,?,?,?,?,?)""",
                (invoice_id, amount_minor, method.strip(), reference, received_at, idempotency_key, request_sha256),
            )
            status = "paid" if paid + amount_minor == invoice["total_minor"] else "partial"
            db.execute("UPDATE invoices SET status=? WHERE id=?", (status, invoice_id))
            self._audit(db, actor, "payment.received", "invoice", invoice_id, received_at,
                        {"amount_minor": amount_minor, "method": method.strip(), "payment_id": cur.lastrowid})
            db.execute(
                """INSERT INTO finance_outbox
                   (event_key,event_type,aggregate_type,aggregate_id,payload_json,created_at,updated_at)
                   VALUES(?,?,?,?,?,?,?)""",
                (f"hospital:payment:{cur.lastrowid}:received:v1", "hospital.payment.received", "payment",
                 str(cur.lastrowid), json.dumps({"payment_id": cur.lastrowid, "invoice_id": invoice_id,
                 "amount_minor": amount_minor, "currency": db.execute(
                     "SELECT currency FROM invoices WHERE id=?", (invoice_id,)
                 ).fetchone()["currency"], "method": method.strip(), "reference": reference,
                 "received_at": received_at}, ensure_ascii=False, sort_keys=True),
                 received_at, received_at),
            )
            db.commit()
            return int(cur.lastrowid)

    def claim_finance_outbox(self, *, now: str, lease_expired_before: str,
                             limit: int = 20) -> list[dict[str, Any]]:
        """Atomically lease pending/retryable events for a future Finance adapter.

        Claiming an event is transport bookkeeping only; it never recognizes
        payments or writes Finance ledger facts.
        """
        if not isinstance(now, str) or not now.strip():
            raise HospitalError("now timestamp is required")
        if not isinstance(lease_expired_before, str) or not lease_expired_before.strip():
            raise HospitalError("lease_expired_before timestamp is required")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise HospitalError("limit must be an integer between 1 and 500")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute(
                """SELECT id,event_key,event_type,aggregate_type,aggregate_id,payload_json,
                          attempts,created_at
                   FROM finance_outbox
                   WHERE status IN ('pending','failed')
                      OR (status='processing' AND updated_at <= ?)
                   ORDER BY id LIMIT ?""",
                (lease_expired_before, limit),
            ).fetchall()
            claimed = []
            for row in rows:
                changed = db.execute(
                    """UPDATE finance_outbox
                       SET status='processing', attempts=attempts+1, updated_at=?, last_error=NULL
                       WHERE id=? AND (
                         status IN ('pending','failed')
                         OR (status='processing' AND updated_at <= ?)
                       )""",
                    (now, row["id"], lease_expired_before),
                ).rowcount
                if changed == 1:
                    item = dict(row)
                    item["attempts"] += 1
                    claimed.append(item)
            db.commit()
            return claimed

    def mark_finance_outbox_delivered(
        self, *, event_key: str, attempts: int, delivered_at: str
    ) -> bool:
        """Mark delivery only for the current lease generation.

        The claim's attempt count is a fencing token: a worker whose lease was
        reclaimed cannot acknowledge a newer worker's delivery.
        """
        if (
            not event_key.strip()
            or not delivered_at.strip()
            or type(attempts) is not int
            or attempts < 1
        ):
            raise HospitalError("event_key, positive attempts and delivered_at are required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            changed = db.execute(
                """UPDATE finance_outbox
                   SET status='delivered', delivered_at=?, updated_at=?, last_error=NULL
                   WHERE event_key=? AND status='processing' AND attempts=?""",
                (delivered_at, delivered_at, event_key, attempts),
            ).rowcount
            db.commit()
            return changed == 1

    def mark_finance_outbox_failed(
        self, *, event_key: str, attempts: int, failed_at: str, error: str
    ) -> bool:
        """Fail only the current lease generation, preserving event identity."""
        if (
            not event_key.strip()
            or not failed_at.strip()
            or not error.strip()
            or type(attempts) is not int
            or attempts < 1
        ):
            raise HospitalError("event_key, positive attempts, failed_at and error are required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            changed = db.execute(
                """UPDATE finance_outbox
                   SET status='failed', updated_at=?, last_error=?
                   WHERE event_key=? AND status='processing' AND attempts=?""",
                (failed_at, error.strip()[:1000], event_key, attempts),
            ).rowcount
            db.commit()
            return changed == 1

    def finance_outbox_status(self) -> dict[str, Any]:
        """Return transport-only outbox counts without exposing event payloads.

        These counters describe Hospital delivery work only. They do not prove
        Finance intake, settlement, recognition, or ledger posting.
        """
        with self.connect() as db:
            counts = {row["status"]: int(row["n"]) for row in db.execute(
                "SELECT status,COUNT(*) AS n FROM finance_outbox GROUP BY status"
            ).fetchall()}
            oldest_pending = db.execute(
                "SELECT MIN(created_at) FROM finance_outbox WHERE status='pending'"
            ).fetchone()[0]
            oldest_failed = db.execute(
                "SELECT MIN(updated_at) FROM finance_outbox WHERE status='failed'"
            ).fetchone()[0]
        for status in ("pending", "processing", "delivered", "failed"):
            counts.setdefault(status, 0)
        return {
            "total": sum(counts.values()),
            "counts": counts,
            "oldest_pending_created_at": oldest_pending,
            "oldest_failed_updated_at": oldest_failed,
            "scope": "hospital_outbox_transport_only",
            "finance_recognition_confirmed": False,
        }

    def dashboard(self) -> dict[str, int]:
        """Operational counts only; excludes patient-identifying data."""
        with self.connect() as db:
            return {
                "active_patients": db.execute("SELECT COUNT(*) FROM patients WHERE active=1").fetchone()[0],
                "active_doctors": db.execute("SELECT COUNT(*) FROM doctors WHERE active=1").fetchone()[0],
                "scheduled_appointments": db.execute("SELECT COUNT(*) FROM appointments WHERE status='scheduled'").fetchone()[0],
                "current_admissions": db.execute("SELECT COUNT(*) FROM admissions WHERE status='admitted'").fetchone()[0],
                "pending_lab_orders": db.execute("SELECT COUNT(*) FROM lab_orders WHERE status IN ('ordered','collected','processing')").fetchone()[0],
                "low_stock_medicines": db.execute("SELECT COUNT(*) FROM medicines WHERE active=1 AND quantity<=reorder_level").fetchone()[0],
                "unpaid_or_partial_invoices": db.execute("SELECT COUNT(*) FROM invoices WHERE status IN ('unpaid','partial')").fetchone()[0],
            }
