import sqlite3
import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError
from hospital.workflows import (
    add_medicine, dispense_prescription, order_lab_test, prescribe_medicine,
    receive_medicine_batch, restock_medicine, update_lab_status,
)


class PharmacyLabHardeningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "hardening.sqlite3")
        self.patient = self.db.register_patient(
            patient_no="TEST-1", full_name="Synthetic Patient",
            created_at="2026-01-01T08:00:00Z", actor="test",
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_stock_and_reorder_level_must_be_integers(self):
        with self.assertRaisesRegex(HospitalError, "non-negative integers"):
            add_medicine(
                self.db, sku="MED-1", name="Synthetic medicine", quantity=1.5,
                reorder_level=0, actor="pharmacist", at="2026-01-01T08:00:00Z",
            )
        with self.assertRaisesRegex(HospitalError, "non-negative integers"):
            add_medicine(
                self.db, sku="MED-2", name="Synthetic medicine", quantity=2,
                reorder_level=True, actor="pharmacist", at="2026-01-01T08:00:00Z",
            )

    def test_stock_ledger_records_receipts_and_dispensing_immutably(self):
        medicine = add_medicine(
            self.db, sku="MED-LEDGER", name="Synthetic medicine", quantity=3,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00Z",
        )
        restock_medicine(
            self.db, medicine_id=medicine, quantity=2, actor="pharmacist",
            at="2026-01-01T09:00:00Z", reference="delivery-42",
        )
        prescription = prescribe_medicine(
            self.db, patient_id=self.patient, medicine_id=medicine, dose="1 tablet",
            frequency="daily", duration="4 days", quantity=4,
            prescribed_at="2026-01-01T10:00:00Z", actor="doctor",
        )
        dispense_prescription(
            self.db, prescription_id=prescription, dispensed_at="2026-01-01T11:00:00Z",
            actor="pharmacist",
        )

        with self.db.connect() as conn:
            rows = conn.execute(
                """SELECT movement_type,quantity_delta,balance_after,reference,actor
                   FROM medicine_stock_movements WHERE medicine_id=? ORDER BY id""",
                (medicine,),
            ).fetchall()
            self.assertEqual(
                [(row["movement_type"], row["quantity_delta"], row["balance_after"])
                 for row in rows],
                [("initial_stock", 3, 3), ("restock", 2, 5), ("dispense", -4, 1)],
            )
            self.assertEqual(rows[1]["reference"], "delivery-42")
            self.assertEqual(rows[2]["actor"], "pharmacist")
            self.assertEqual(
                conn.execute("SELECT quantity FROM medicines WHERE id=?", (medicine,)).fetchone()[0],
                1,
            )
            with self.assertRaisesRegex(sqlite3.IntegrityError, "append-only"):
                conn.execute(
                    "UPDATE medicine_stock_movements SET quantity_delta=0 WHERE medicine_id=?",
                    (medicine,),
                )
            with self.assertRaisesRegex(sqlite3.IntegrityError, "append-only"):
                conn.execute(
                    "DELETE FROM medicine_stock_movements WHERE medicine_id=?",
                    (medicine,),
                )

    def test_batch_expiry_is_tracked_and_dispensing_uses_fefo(self):
        medicine = add_medicine(
            self.db, sku="MED-FEFO", name="Synthetic medicine", quantity=0,
            reorder_level=1, actor="pharmacist", at="2026-01-01T08:00:00Z",
        )
        receive_medicine_batch(
            self.db, medicine_id=medicine, batch_no="LATE-EXPIRY", quantity=3,
            expiry_date="2027-06-30", actor="pharmacist", at="2026-01-02T08:00:00Z",
        )
        receive_medicine_batch(
            self.db, medicine_id=medicine, batch_no="EARLY-EXPIRY", quantity=2,
            expiry_date="2026-12-31", actor="pharmacist", at="2026-01-02T09:00:00Z",
        )
        prescription = prescribe_medicine(
            self.db, patient_id=self.patient, medicine_id=medicine, dose="1 tablet",
            frequency="daily", duration="2 days", quantity=4,
            prescribed_at="2026-01-02T10:00:00Z", actor="doctor",
        )
        dispense_prescription(
            self.db, prescription_id=prescription, dispensed_at="2026-01-03T08:00:00Z",
            actor="pharmacist",
        )
        with self.db.connect() as conn:
            lots = conn.execute(
                "SELECT batch_no,quantity FROM medicine_batches WHERE medicine_id=? ORDER BY expiry_date",
                (medicine,),
            ).fetchall()
            self.assertEqual([(row["batch_no"], row["quantity"]) for row in lots],
                             [("EARLY-EXPIRY", 0), ("LATE-EXPIRY", 1)])
            movements = conn.execute(
                "SELECT batch_id,quantity_delta FROM medicine_batch_movements WHERE movement_type='dispense'"
            ).fetchall()
            self.assertEqual([row["quantity_delta"] for row in movements], [-2, -2])

    def test_expired_batch_cannot_be_dispensed(self):
        medicine = add_medicine(
            self.db, sku="MED-EXPIRED", name="Expired medicine", quantity=0,
            reorder_level=0, actor="pharmacist", at="2026-01-01T08:00:00Z",
        )
        receive_medicine_batch(
            self.db, medicine_id=medicine, batch_no="EXPIRED-LOT", quantity=2,
            expiry_date="2025-12-31", actor="pharmacist", at="2026-01-01T08:00:00Z",
        )
        prescription = prescribe_medicine(
            self.db, patient_id=self.patient, medicine_id=medicine, dose="1 tablet",
            frequency="daily", duration="1 day", quantity=1,
            prescribed_at="2026-01-01T09:00:00Z", actor="doctor",
        )
        with self.assertRaisesRegex(HospitalError, "non-expired batch stock"):
            dispense_prescription(
                self.db, prescription_id=prescription, dispensed_at="2026-01-02T08:00:00Z",
                actor="pharmacist",
            )

    def test_lab_event_cannot_precede_order_across_time_zones(self):
        order = order_lab_test(
            self.db, patient_id=self.patient, test_name="Synthetic test",
            ordered_at="2026-01-02T10:00:00+02:00", actor="doctor",
        )
        with self.assertRaisesRegex(HospitalError, "cannot precede"):
            update_lab_status(
                self.db, order_id=order, status="collected", actor="lab",
                at="2026-01-02T07:59:00Z",
            )

    def test_lab_event_at_same_instant_in_utc_is_allowed(self):
        order = order_lab_test(
            self.db, patient_id=self.patient, test_name="Synthetic test",
            ordered_at="2026-01-02T10:00:00+02:00", actor="doctor",
        )
        update_lab_status(
            self.db, order_id=order, status="collected", actor="lab",
            at="2026-01-02T08:00:00Z",
        )


if __name__ == "__main__":
    unittest.main()
