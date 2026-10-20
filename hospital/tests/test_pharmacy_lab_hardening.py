import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB, HospitalError
from hospital.workflows import add_medicine, order_lab_test, update_lab_status


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
