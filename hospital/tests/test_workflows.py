import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError
from hospital.workflows import (
    add_medicine, close_encounter, create_encounter, create_purchase_order,
    create_supplier, dispense_prescription, order_lab_test, prescribe_medicine,
    receive_purchase_order, adjust_stock, update_lab_status,
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
        with self.db.connect() as conn:
            final_rows = conn.execute(
                """SELECT description,diagnosis_type,recorded_by,recorded_at
                   FROM encounter_diagnoses WHERE encounter_id=?""",
                (encounter,),
            ).fetchall()
        self.assertEqual(len(final_rows), 1)
        self.assertEqual(tuple(final_rows[0]),
                         ("Example diagnosis", "final", "doctor", "2026-01-02T08:30:00"))
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


    def test_purchase_order_rejects_fractional_and_boolean_quantities_or_costs(self):
        medicine = add_medicine(self.db, sku="MED-PO-1", name="Purchase item", quantity=0,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00")
        supplier = create_supplier(self.db, supplier_no="SUP-1", name="Supplier",
            actor="admin", at="2026-01-01T08:00:00")
        invalid_items = [
            [{"medicine_id": medicine, "quantity": 1.5, "unit_cost_minor": 10}],
            [{"medicine_id": medicine, "quantity": True, "unit_cost_minor": 10}],
            [{"medicine_id": medicine, "quantity": 1, "unit_cost_minor": 2.5}],
            [{"medicine_id": medicine, "quantity": 1, "unit_cost_minor": False}],
        ]
        for items in invalid_items:
            with self.subTest(items=items):
                with self.assertRaises(HospitalError):
                    create_purchase_order(self.db, order_no="PO-INVALID", supplier_id=supplier,
                        items=items, actor="admin", at="2026-01-01T08:10:00")

    def test_purchase_receipt_is_atomic_and_inactive_medicine_stock_is_immutable(self):
        first = add_medicine(self.db, sku="MED-ATOMIC-1", name="First item", quantity=2,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00")
        second = add_medicine(self.db, sku="MED-ATOMIC-2", name="Second item", quantity=4,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00")
        supplier = create_supplier(self.db, supplier_no="SUP-ATOMIC", name="Atomic supplier",
            actor="admin", at="2026-01-01T08:00:00")
        order = create_purchase_order(self.db, order_no="PO-ATOMIC", supplier_id=supplier,
            items=[
                {"medicine_id": first, "quantity": 3, "unit_cost_minor": 100},
                {"medicine_id": second, "quantity": 5, "unit_cost_minor": 200},
            ], actor="admin", at="2026-01-01T08:10:00")

        with self.db.connect() as conn:
            conn.execute("UPDATE medicines SET active=0 WHERE id=?", (second,))
            quantities_before = {
                row["id"]: row["quantity"]
                for row in conn.execute("SELECT id,quantity FROM medicines WHERE id IN (?,?)", (first, second))
            }
            movement_count_before = conn.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0]
            receipt_audits_before = conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE action='purchase_order.received' AND entity_id=?",
                (str(order),),
            ).fetchone()[0]

        with self.assertRaisesRegex(HospitalError, "active medicine not found"):
            receive_purchase_order(self.db, order_id=order, actor="admin", at="2026-01-01T08:20:00")

        with self.db.connect() as conn:
            quantities_after = {
                row["id"]: row["quantity"]
                for row in conn.execute("SELECT id,quantity FROM medicines WHERE id IN (?,?)", (first, second))
            }
            movement_count_after = conn.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0]
            order_status = conn.execute("SELECT status,received_at FROM purchase_orders WHERE id=?", (order,)).fetchone()
            receipt_audits_after = conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE action='purchase_order.received' AND entity_id=?",
                (str(order),),
            ).fetchone()[0]

        self.assertEqual(quantities_after, quantities_before)
        self.assertEqual(movement_count_after, movement_count_before)
        self.assertEqual(tuple(order_status), ("ordered", None))
        self.assertEqual(receipt_audits_after, receipt_audits_before)

        with self.assertRaisesRegex(HospitalError, "active medicine not found"):
            adjust_stock(self.db, medicine_id=second, quantity_delta=1,
                actor="pharmacist", at="2026-01-01T08:30:00")
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT quantity FROM medicines WHERE id=?", (second,)).fetchone()["quantity"],
                quantities_before[second],
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0],
                movement_count_before,
            )

    def test_purchase_order_accepts_integer_minor_units(self):
        medicine = add_medicine(self.db, sku="MED-PO-2", name="Purchase item 2", quantity=0,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00")
        supplier = create_supplier(self.db, supplier_no="SUP-2", name="Supplier 2",
            actor="admin", at="2026-01-01T08:00:00")
        order = create_purchase_order(self.db, order_no="PO-VALID", supplier_id=supplier,
            items=[{"medicine_id": medicine, "quantity": 3, "unit_cost_minor": 125}],
            actor="admin", at="2026-01-01T08:10:00")
        with self.db.connect() as conn:
            row = conn.execute("SELECT quantity,unit_cost_minor FROM purchase_order_items WHERE purchase_order_id=?",
                (order,)).fetchone()
        self.assertEqual((row["quantity"], row["unit_cost_minor"]), (3, 125))

if __name__ == "__main__":
    unittest.main()
