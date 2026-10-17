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


if __name__ == "__main__":
    unittest.main()
