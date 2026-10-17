"""Regression tests for the isolated hospital core; run: python -m unittest discover -s hospital/tests."""
import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError


class HospitalCoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "test.sqlite3")
        self.patient = self.db.register_patient(
            patient_no="P-001", full_name="Test Patient", created_at="2026-01-01T08:00:00",
            actor="test-user")
        self.doctor = self.db.register_doctor(
            employee_no="D-001", full_name="Test Doctor", specialty="General",
            actor="test-user", at="2026-01-01T08:00:00")

    def tearDown(self):
        self.temp.cleanup()

    def test_patient_and_doctor_are_counted(self):
        counts = self.db.dashboard()
        self.assertEqual(counts["active_patients"], 1)
        self.assertEqual(counts["active_doctors"], 1)

    def test_overlapping_doctor_appointments_are_rejected(self):
        self.db.book_appointment(patient_id=self.patient, doctor_id=self.doctor,
            starts_at="2026-01-02T09:00:00", ends_at="2026-01-02T09:30:00",
            created_at="2026-01-01T08:00:00", actor="reception")
        with self.assertRaises(HospitalError):
            self.db.book_appointment(patient_id=self.patient, doctor_id=self.doctor,
                starts_at="2026-01-02T09:15:00", ends_at="2026-01-02T10:00:00",
                created_at="2026-01-01T08:01:00", actor="reception")

    def test_database_rejects_overlapping_appointments_from_direct_sql(self):
        import sqlite3

        with self.db.connect() as conn:
            conn.execute(
                "INSERT INTO appointments(patient_id,doctor_id,starts_at,ends_at,created_at) "
                "VALUES(?,?,?,?,?)",
                (self.patient, self.doctor, "2026-01-02T09:00:00",
                 "2026-01-02T09:30:00", "2026-01-01T08:00:00"),
            )
            conn.execute(
                "INSERT INTO appointments(patient_id,doctor_id,starts_at,ends_at,created_at) "
                "VALUES(?,?,?,?,?)",
                (self.patient, self.doctor, "2026-01-02T10:00:00",
                 "2026-01-02T10:30:00", "2026-01-01T08:01:00"),
            )
            with self.assertRaisesRegex(sqlite3.IntegrityError, "overlapping appointment"):
                conn.execute(
                    "INSERT INTO appointments(patient_id,doctor_id,starts_at,ends_at,created_at) "
                    "VALUES(?,?,?,?,?)",
                    (self.patient, self.doctor, "2026-01-02T09:15:00",
                     "2026-01-02T10:00:00", "2026-01-01T08:02:00"),
                )
            with self.assertRaisesRegex(sqlite3.IntegrityError, "overlapping appointment"):
                conn.execute(
                    "UPDATE appointments SET starts_at=?,ends_at=? WHERE id=1",
                    ("2026-01-02T10:10:00", "2026-01-02T10:20:00"),
                )

    def test_database_rejects_duplicate_active_admissions_from_direct_sql(self):
        import sqlite3

        with self.db.connect() as conn:
            conn.execute("INSERT INTO beds(ward,bed_no) VALUES('A','01')")
            bed_id = conn.execute("SELECT id FROM beds").fetchone()["id"]
            conn.execute(
                "INSERT INTO admissions(patient_id,bed_id,admitted_at) VALUES(?,?,?)",
                (self.patient, bed_id, "2026-01-02T09:00:00"),
            )
            with self.assertRaisesRegex(sqlite3.IntegrityError, "bed is already occupied"):
                conn.execute(
                    "INSERT INTO admissions(patient_id,bed_id,admitted_at) VALUES(?,?,?)",
                    (self.patient, bed_id, "2026-01-02T10:00:00"),
                )
            conn.execute("INSERT INTO patients(patient_no,full_name,created_at) VALUES(?,?,?)",
                         ("P-002", "Second Patient", "2026-01-01T08:00:00"))
            second_patient = conn.execute(
                "SELECT id FROM patients WHERE patient_no='P-002'"
            ).fetchone()["id"]
            with self.assertRaisesRegex(sqlite3.IntegrityError, "bed is already occupied"):
                conn.execute(
                    "INSERT INTO admissions(patient_id,bed_id,admitted_at) VALUES(?,?,?)",
                    (second_patient, bed_id, "2026-01-02T11:00:00"),
                )

    def test_booking_integrity_triggers_exist_after_reopening_database(self):
        self.db = HospitalDB(self.db.path)
        with self.db.connect() as conn:
            names = {
                row["name"] for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='trigger'"
                )
            }
        self.assertTrue({
            "appointments_no_overlap_insert",
            "appointments_no_overlap_update",
            "admissions_one_active_bed_insert",
            "admissions_one_active_bed_update",
        }.issubset(names))

    def test_bed_cannot_be_double_booked_and_can_be_reused_after_discharge(self):
        with self.db.connect() as conn:
            conn.execute("INSERT INTO beds(ward,bed_no) VALUES('A','01')")
            bed_id = conn.execute("SELECT id FROM beds").fetchone()["id"]
        admission = self.db.admit_patient(patient_id=self.patient, bed_id=bed_id,
            admitted_at="2026-01-02T09:00:00", actor="nurse")
        with self.assertRaises(HospitalError):
            self.db.admit_patient(patient_id=self.patient, bed_id=bed_id,
                admitted_at="2026-01-02T10:00:00", actor="nurse")
        self.db.discharge_patient(admission_id=admission,
            discharged_at="2026-01-03T09:00:00", actor="nurse")
        self.db.admit_patient(patient_id=self.patient, bed_id=bed_id,
            admitted_at="2026-01-04T09:00:00", actor="nurse")

    def test_payment_cannot_exceed_invoice_and_updates_status(self):
        invoice = self.db.create_invoice(invoice_no="INV-001", patient_id=self.patient,
            total_minor=10000, created_at="2026-01-01T08:00:00", actor="cashier")
        self.db.record_payment(invoice_id=invoice, amount_minor=4000, method="cash",
            received_at="2026-01-01T09:00:00", actor="cashier")
        with self.assertRaises(HospitalError):
            self.db.record_payment(invoice_id=invoice, amount_minor=7000, method="cash",
                received_at="2026-01-01T09:05:00", actor="cashier")
        self.db.record_payment(invoice_id=invoice, amount_minor=6000, method="cash",
            received_at="2026-01-01T09:10:00", actor="cashier")
        with self.db.connect() as conn:
            status = conn.execute("SELECT status FROM invoices WHERE id=?", (invoice,)).fetchone()["status"]
        self.assertEqual(status, "paid")

    def test_patient_registration_is_audited(self):
        with self.db.connect() as conn:
            row = conn.execute("SELECT action,entity_type FROM audit_log ORDER BY id LIMIT 1").fetchone()
        self.assertEqual((row["action"], row["entity_type"]), ("patient.registered", "patient"))

    def test_audit_log_rejects_updates_and_deletes(self):
        import sqlite3

        with self.db.connect() as conn:
            before = conn.execute(
                "SELECT id,actor,action,entity_type,entity_id,details_json,occurred_at "
                "FROM audit_log ORDER BY id LIMIT 1"
            ).fetchone()
            with self.assertRaisesRegex(sqlite3.IntegrityError, "audit_log is append-only"):
                conn.execute("UPDATE audit_log SET action='rewritten' WHERE id=?", (before["id"],))
            with self.assertRaisesRegex(sqlite3.IntegrityError, "audit_log is append-only"):
                conn.execute("DELETE FROM audit_log WHERE id=?", (before["id"],))
            after = conn.execute(
                "SELECT id,actor,action,entity_type,entity_id,details_json,occurred_at "
                "FROM audit_log ORDER BY id LIMIT 1"
            ).fetchone()
        self.assertEqual(tuple(after), tuple(before))
        self.assertEqual(after["action"], "patient.registered")

    def test_audit_log_append_only_triggers_are_created_for_existing_databases(self):
        # Reinitializing an existing database should install missing triggers.
        self.db = HospitalDB(self.db.path)
        with self.db.connect() as conn:
            names = {
                row["name"] for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='trigger'"
                )
            }
        self.assertIn("hospital_audit_log_no_update", names)
        self.assertIn("hospital_audit_log_no_delete", names)


if __name__ == "__main__":
    unittest.main()
