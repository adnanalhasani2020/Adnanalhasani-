import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError
from hospital.workflows import (
    add_medicine, close_encounter, create_encounter, dispense_prescription,
    order_lab_test, prescribe_medicine, restock_medicine, update_lab_status,
)


class HospitalWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "workflows.sqlite3")
        self.patient = self.db.register_patient(patient_no="P-100", full_name="Sample Patient",
            created_at="2026-01-01T08:00:00", actor="test")
        self.doctor = self.db.register_doctor(employee_no="D-100", full_name="Sample Doctor",
            specialty="General", actor="test", at="2026-01-01T08:00:00")

    def tearDown(self):
        self.temp.cleanup()

    def test_encounter_lifecycle(self):
        encounter = create_encounter(self.db, patient_id=self.patient, doctor_id=self.doctor,
            opened_at="2026-01-02T08:00:00", actor="doctor", chief_complaint="Cough")
        close_encounter(self.db, encounter_id=encounter, closed_at="2026-01-02T08:30:00",
            actor="doctor", diagnosis="Example diagnosis", clinical_notes="Follow up")
        with self.db.connect() as conn:
            row = conn.execute("SELECT status,diagnosis FROM encounters WHERE id=?", (encounter,)).fetchone()
        self.assertEqual((row["status"], row["diagnosis"]), ("closed", "Example diagnosis"))
        with self.assertRaises(HospitalError):
            close_encounter(self.db, encounter_id=encounter, closed_at="2026-01-02T08:40:00",
                actor="doctor", diagnosis="Duplicate close")

    def test_encounter_appointment_doctor_must_match(self):
        other_doctor = self.db.register_doctor(employee_no="D-101", full_name="Other Doctor",
            specialty="General", actor="test", at="2026-01-01T08:00:00")
        appointment = self.db.book_appointment(patient_id=self.patient, doctor_id=self.doctor,
            starts_at="2026-01-02T08:00:00", ends_at="2026-01-02T08:30:00",
            created_at="2026-01-01T08:00:00", actor="reception")
        with self.assertRaisesRegex(HospitalError, "encounter doctor must match"):
            create_encounter(self.db, patient_id=self.patient, doctor_id=other_doctor,
                opened_at="2026-01-02T08:05:00", actor="doctor", appointment_id=appointment)
        encounter = create_encounter(self.db, patient_id=self.patient, doctor_id=self.doctor,
            opened_at="2026-01-02T08:05:00", actor="doctor", appointment_id=appointment)
        with self.db.connect() as conn:
            row = conn.execute("SELECT doctor_id,appointment_id FROM encounters WHERE id=?",
                               (encounter,)).fetchone()
        self.assertEqual((row["doctor_id"], row["appointment_id"]), (self.doctor, appointment))

    def test_lab_order_rejects_inactive_or_unknown_doctor(self):
        with self.assertRaisesRegex(HospitalError, "active ordering doctor"):
            order_lab_test(self.db, patient_id=self.patient, test_name="CBC",
                ordered_at="2026-01-02T08:00:00Z", actor="doctor", doctor_id=999999)

    def test_lab_result_requires_content(self):
        order = order_lab_test(self.db, patient_id=self.patient, test_name="CBC",
            ordered_at="2026-01-02T08:00:00", actor="doctor", doctor_id=self.doctor)
        with self.assertRaises(HospitalError):
            update_lab_status(self.db, order_id=order, status="resulted", actor="lab",
                at="2026-01-02T09:00:00", result="")
        update_lab_status(self.db, order_id=order, status="collected", actor="lab",
            at="2026-01-02T08:30:00")
        update_lab_status(self.db, order_id=order, status="processing", actor="lab",
            at="2026-01-02T08:45:00")
        update_lab_status(self.db, order_id=order, status="resulted", actor="lab",
            at="2026-01-02T09:00:00", result="Example result")
        with self.db.connect() as conn:
            row = conn.execute("SELECT status,result FROM lab_orders WHERE id=?", (order,)).fetchone()
        self.assertEqual((row["status"], row["result"]), ("resulted", "Example result"))

    def test_lab_events_must_be_chronological_not_only_after_order(self):
        order = order_lab_test(self.db, patient_id=self.patient, test_name="CBC",
            ordered_at="2026-01-02T08:00:00Z", actor="doctor", doctor_id=self.doctor)
        update_lab_status(self.db, order_id=order, status="collected", actor="lab",
            at="2026-01-02T08:30:00Z")
        with self.assertRaisesRegex(HospitalError, "previous lab event"):
            update_lab_status(self.db, order_id=order, status="processing", actor="lab",
                at="2026-01-02T08:20:00Z")
        update_lab_status(self.db, order_id=order, status="processing", actor="lab",
            at="2026-01-02T08:45:00Z")
        with self.db.connect() as conn:
            events = conn.execute(
                "SELECT status FROM lab_order_events WHERE lab_order_id=? ORDER BY id",
                (order,),
            ).fetchall()
        self.assertEqual([event["status"] for event in events],
            ["ordered", "collected", "processing"])

    def test_lab_status_transitions_must_follow_workflow_order(self):
        order = order_lab_test(self.db, patient_id=self.patient, test_name="CBC",
            ordered_at="2026-01-02T08:00:00", actor="doctor", doctor_id=self.doctor)
        with self.assertRaisesRegex(HospitalError, "invalid lab status transition"):
            update_lab_status(self.db, order_id=order, status="resulted", actor="lab",
                at="2026-01-02T08:30:00", result="Premature result")
        update_lab_status(self.db, order_id=order, status="collected", actor="lab",
            at="2026-01-02T08:10:00")
        with self.assertRaisesRegex(HospitalError, "invalid lab status transition"):
            update_lab_status(self.db, order_id=order, status="collected", actor="lab",
                at="2026-01-02T08:15:00")
        update_lab_status(self.db, order_id=order, status="processing", actor="lab",
            at="2026-01-02T08:20:00")
        update_lab_status(self.db, order_id=order, status="resulted", actor="lab",
            at="2026-01-02T08:30:00", result="Example result")
        with self.assertRaisesRegex(HospitalError, "invalid lab status transition"):
            update_lab_status(self.db, order_id=order, status="cancelled", actor="lab",
                at="2026-01-02T08:40:00")

    def test_restock_adds_stock_and_audits_receipt(self):
        medicine = add_medicine(self.db, sku="MED-R", name="Restock test", quantity=2,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00Z")
        total = restock_medicine(self.db, medicine_id=medicine, quantity=5,
            actor="pharmacist", at="2026-01-02T08:00:00Z", reference="GRN-100")
        self.assertEqual(total, 7)
        with self.db.connect() as conn:
            row = conn.execute("SELECT action,details_json FROM audit_log WHERE entity_type='medicine' AND entity_id=? ORDER BY id DESC LIMIT 1", (str(medicine),)).fetchone()
        self.assertEqual(row["action"], "medicine.stock_received")
        self.assertIn("GRN-100", row["details_json"])

    def test_restock_rejects_non_integer_quantity(self):
        medicine = add_medicine(self.db, sku="MED-R2", name="Restock test", quantity=2,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00Z")
        with self.assertRaisesRegex(HospitalError, "positive integer"):
            restock_medicine(self.db, medicine_id=medicine, quantity=1.5,
                actor="pharmacist", at="2026-01-02T08:00:00Z")

    def test_dispensing_cannot_precede_prescription_timestamp(self):
        medicine = add_medicine(self.db, sku="MED-TIME", name="Timestamp test", quantity=5,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00Z")
        prescription = prescribe_medicine(self.db, patient_id=self.patient, medicine_id=medicine,
            dose="1 tablet", frequency="daily", duration="2 days", quantity=1,
            prescribed_at="2026-01-02T08:00:00Z", actor="doctor")
        with self.assertRaisesRegex(HospitalError, "cannot precede"):
            dispense_prescription(self.db, prescription_id=prescription,
                dispensed_at="2026-01-02T07:59:00Z", actor="pharmacist")

    def test_dispensing_deducts_stock_once_and_rejects_insufficient_stock(self):
        medicine = add_medicine(self.db, sku="MED-1", name="Test medicine", quantity=5,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00")
        prescription = prescribe_medicine(self.db, patient_id=self.patient, medicine_id=medicine,
            dose="1 tablet", frequency="daily", duration="3 days", quantity=3,
            prescribed_at="2026-01-02T08:00:00", actor="doctor")
        dispense_prescription(self.db, prescription_id=prescription,
            dispensed_at="2026-01-02T09:00:00", actor="pharmacist")
        with self.db.connect() as conn:
            stock = conn.execute("SELECT quantity FROM medicines WHERE id=?", (medicine,)).fetchone()["quantity"]
        self.assertEqual(stock, 2)
        with self.assertRaises(HospitalError):
            dispense_prescription(self.db, prescription_id=prescription,
                dispensed_at="2026-01-02T09:05:00", actor="pharmacist")
        second = prescribe_medicine(self.db, patient_id=self.patient, medicine_id=medicine,
            dose="2 tablets", frequency="daily", duration="2 days", quantity=4,
            prescribed_at="2026-01-02T10:00:00", actor="doctor")
        with self.assertRaises(HospitalError):
            dispense_prescription(self.db, prescription_id=second,
                dispensed_at="2026-01-02T10:05:00", actor="pharmacist")


if __name__ == "__main__":
    unittest.main()
