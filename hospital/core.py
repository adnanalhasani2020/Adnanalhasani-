"""Minimal, self-contained hospital operations core (SQLite, standard library only).

This is a foundation for development/testing, not a certified clinical system.
All timestamps are ISO-8601 strings supplied by the caller.
"""
from __future__ import annotations

import json
import sqlite3
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
CREATE TABLE IF NOT EXISTS encounters (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  doctor_id INTEGER REFERENCES doctors(id), appointment_id INTEGER REFERENCES appointments(id),
  opened_at TEXT NOT NULL, closed_at TEXT, chief_complaint TEXT NOT NULL DEFAULT '',
  diagnosis TEXT NOT NULL DEFAULT '', clinical_notes TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','closed'))
);
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
CREATE TABLE IF NOT EXISTS lab_orders (
  id INTEGER PRIMARY KEY, patient_id INTEGER NOT NULL REFERENCES patients(id),
  encounter_id INTEGER REFERENCES encounters(id), ordered_by INTEGER REFERENCES doctors(id),
  test_name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ordered'
    CHECK(status IN ('ordered','collected','processing','resulted','cancelled')),
  result TEXT, ordered_at TEXT NOT NULL, resulted_at TEXT
);
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
CREATE TABLE IF NOT EXISTS invoices (
  id INTEGER PRIMARY KEY, invoice_no TEXT NOT NULL UNIQUE,
  patient_id INTEGER NOT NULL REFERENCES patients(id),
  encounter_id INTEGER REFERENCES encounters(id), total_minor INTEGER NOT NULL CHECK(total_minor >= 0),
  currency TEXT NOT NULL DEFAULT 'YER',
  status TEXT NOT NULL DEFAULT 'unpaid' CHECK(status IN ('unpaid','partial','paid','void')),
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY, invoice_id INTEGER NOT NULL REFERENCES invoices(id),
  amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), method TEXT NOT NULL,
  reference TEXT, received_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY, actor TEXT NOT NULL, action TEXT NOT NULL,
  entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
  details_json TEXT NOT NULL DEFAULT '{}', occurred_at TEXT NOT NULL
);
"""


class HospitalError(ValueError):
    """A validated business-rule or input error."""


class HospitalDB:
    def __init__(self, path: str | Path = "hospital.sqlite3"):
        self.path = str(path)
        with self.connect() as db:
            db.executescript(SCHEMA)

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
            cur = db.execute("""INSERT INTO appointments
                (patient_id,doctor_id,starts_at,ends_at,reason,created_at)
                VALUES(?,?,?,?,?,?)""",
                (patient_id, doctor_id, starts_at, ends_at, reason, created_at))
            aid = cur.lastrowid
            self._audit(db, actor, "appointment.booked", "appointment", aid, created_at)
            db.commit()
            return int(aid)

    def admit_patient(self, *, patient_id: int, bed_id: int, admitted_at: str,
                      actor: str, reason: str = "") -> int:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            patient = db.execute("SELECT active FROM patients WHERE id=?", (patient_id,)).fetchone()
            bed = db.execute("SELECT active FROM beds WHERE id=?", (bed_id,)).fetchone()
            occupied = db.execute("SELECT 1 FROM admissions WHERE bed_id=? AND status='admitted'",
                                  (bed_id,)).fetchone()
            if patient is None or not patient["active"]:
                raise HospitalError("active patient not found")
            if bed is None or not bed["active"]:
                raise HospitalError("active bed not found")
            if occupied:
                raise HospitalError("bed is already occupied")
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
                       encounter_id: int | None = None) -> int:
        if not invoice_no.strip() or total_minor < 0:
            raise HospitalError("invoice number is required and total must be non-negative")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cur = db.execute("""INSERT INTO invoices(invoice_no,patient_id,encounter_id,total_minor,currency,created_at)
                VALUES(?,?,?,?,?,?)""",
                (invoice_no.strip(), patient_id, encounter_id, total_minor, currency, created_at))
            ident = cur.lastrowid
            self._audit(db, actor, "invoice.created", "invoice", ident, created_at,
                        {"total_minor": total_minor, "currency": currency})
            db.commit()
            return int(ident)

    def record_payment(self, *, invoice_id: int, amount_minor: int, method: str,
                       received_at: str, actor: str, reference: str | None = None) -> int:
        if amount_minor <= 0 or not method.strip():
            raise HospitalError("payment amount must be positive and method is required")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            invoice = db.execute("SELECT total_minor,status FROM invoices WHERE id=?", (invoice_id,)).fetchone()
            if invoice is None or invoice["status"] == "void":
                raise HospitalError("valid invoice not found")
            paid = db.execute("SELECT COALESCE(SUM(amount_minor),0) AS n FROM payments WHERE invoice_id=?",
                              (invoice_id,)).fetchone()["n"]
            if paid + amount_minor > invoice["total_minor"]:
                raise HospitalError("payment exceeds invoice balance")
            cur = db.execute("""INSERT INTO payments(invoice_id,amount_minor,method,reference,received_at)
                VALUES(?,?,?,?,?)""", (invoice_id, amount_minor, method.strip(), reference, received_at))
            status = "paid" if paid + amount_minor == invoice["total_minor"] else "partial"
            db.execute("UPDATE invoices SET status=? WHERE id=?", (status, invoice_id))
            self._audit(db, actor, "payment.received", "invoice", invoice_id, received_at,
                        {"amount_minor": amount_minor, "method": method, "payment_id": cur.lastrowid})
            db.commit()
            return int(cur.lastrowid)

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
