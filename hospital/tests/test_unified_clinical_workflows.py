import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from hospital.api import make_handler
from hospital.core import HospitalDB


class UnifiedClinicalWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "clinical.sqlite3")
        self.token = "clinical-workflow-test-token-long-enough-123456789"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.db, self.token))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.patient = self.post("/api/patients", {
            "patient_no": "UC-P-1", "full_name": "Test Patient", "created_at": "2026-02-01T08:00:00"
        })["id"]
        self.doctor = self.post("/api/doctors", {
            "employee_no": "UC-D-1", "full_name": "Test Doctor", "specialty": "General",
            "at": "2026-02-01T08:00:00"
        })["id"]
        self.encounter = self.post("/api/encounters", {
            "patient_id": self.patient, "doctor_id": self.doctor,
            "opened_at": "2026-02-01T09:00:00", "chief_complaint": "test visit"
        })["id"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def call(self, path, payload=None, method="POST"):
        headers = {"Authorization": f"Bearer {self.token}"}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        req = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def post(self, path, payload):
        status, data = self.call(path, payload)
        if status not in (200, 201):
            raise AssertionError(f"{path} returned {status}: {data}")
        return data

    def test_diagnosis_allergy_vitals_and_nursing_are_linked(self):
        diagnosis = self.post("/api/diagnoses", {
            "encounter_id": self.encounter, "description": "Test diagnosis",
            "diagnosis_type": "final", "at": "2026-02-01T09:05:00"
        })
        allergy = self.post("/api/allergies", {
            "patient_id": self.patient, "substance": "Example substance",
            "reaction": "rash", "severity": "moderate", "at": "2026-02-01T09:06:00"
        })
        vitals = self.post("/api/vitals", {
            "encounter_id": self.encounter, "measured_at": "2026-02-01T09:07:00",
            "temperature_c": 37.1, "pulse_bpm": 75, "systolic_bp": 120,
            "diastolic_bp": 80, "oxygen_saturation": 98
        })
        note = self.post("/api/nursing-notes", {
            "encounter_id": self.encounter, "note_type": "observation",
            "note": "Routine observation", "at": "2026-02-01T09:08:00"
        })
        self.assertTrue(all(x["id"] > 0 for x in (diagnosis, allergy, vitals, note)))
        status, report = self.call("/api/reports", method="GET")
        self.assertEqual(status, 200, report)
        self.assertEqual(report["data"]["clinical"]["diagnoses_recorded"], 1)
        self.assertEqual(report["data"]["clinical"]["active_allergies"], 1)
        self.assertEqual(report["data"]["clinical"]["vital_observations"], 1)
        self.assertEqual(report["data"]["clinical"]["nursing_notes"], 1)
        with self.db.connect() as conn:
            encounter = conn.execute("SELECT diagnosis FROM encounters WHERE id=?", (self.encounter,)).fetchone()
        self.assertEqual(encounter["diagnosis"], "Test diagnosis")

    def test_vitals_reject_mismatched_patient_and_invalid_measurement(self):
        other = self.post("/api/patients", {
            "patient_no": "UC-P-2", "full_name": "Other Patient", "created_at": "2026-02-01T08:00:00"
        })["id"]
        status, _ = self.call("/api/vitals", {
            "encounter_id": self.encounter, "patient_id": other,
            "measured_at": "2026-02-01T09:07:00", "pulse_bpm": 70
        })
        self.assertEqual(status, 400)
        status, _ = self.call("/api/vitals", {
            "encounter_id": self.encounter, "measured_at": "2026-02-01T09:07:00",
            "oxygen_saturation": 140
        })
        self.assertEqual(status, 400)

    def test_purchase_receipt_updates_stock_once_and_ledger(self):
        medicine = self.post("/api/medicines", {
            "sku": "UC-MED-1", "name": "Test medicine", "quantity": 4,
            "reorder_level": 2, "at": "2026-02-01T08:00:00"
        })["id"]
        supplier = self.post("/api/suppliers", {
            "supplier_no": "UC-SUP-1", "name": "Test supplier", "at": "2026-02-01T08:00:00"
        })["id"]
        order = self.post("/api/purchase-orders", {
            "order_no": "UC-PO-1", "supplier_id": supplier, "at": "2026-02-01T10:00:00",
            "items": [{"medicine_id": medicine, "quantity": 6, "unit_cost_minor": 250}]
        })["id"]
        status, data = self.call(f"/api/purchase-orders/{order}/receive", {"at": "2026-02-02T10:00:00"})
        self.assertEqual(status, 200, data)
        status, data = self.call(f"/api/purchase-orders/{order}/receive", {"at": "2026-02-02T10:05:00"})
        self.assertEqual(status, 400)
        with self.db.connect() as conn:
            stock = conn.execute("SELECT quantity FROM medicines WHERE id=?", (medicine,)).fetchone()["quantity"]
            movement = conn.execute("SELECT quantity_delta,movement_type FROM inventory_movements WHERE medicine_id=? ORDER BY id DESC LIMIT 1", (medicine,)).fetchone()
        self.assertEqual(stock, 10)
        self.assertEqual((movement["quantity_delta"], movement["movement_type"]), (6, "purchase"))

    def test_admission_links_to_same_patient_encounter(self):
        bed = self.post("/api/beds", {"ward": "UC-A", "bed_no": "01", "at": "2026-02-01T08:00:00"})["id"]
        admission = self.post("/api/admissions", {
            "patient_id": self.patient, "bed_id": bed, "encounter_id": self.encounter,
            "admitted_at": "2026-02-01T10:00:00"
        })["id"]
        with self.db.connect() as conn:
            row = conn.execute("SELECT encounter_id FROM admissions WHERE id=?", (admission,)).fetchone()
        self.assertEqual(row["encounter_id"], self.encounter)

    def test_inventory_adjustment_prevents_negative_stock(self):
        medicine = self.post("/api/medicines", {
            "sku": "UC-MED-2", "name": "Stock test", "quantity": 2,
            "reorder_level": 0, "at": "2026-02-01T08:00:00"
        })["id"]
        status, _ = self.call("/api/inventory-adjustments", {
            "medicine_id": medicine, "quantity_delta": -3, "at": "2026-02-01T11:00:00"
        })
        self.assertEqual(status, 400)
        status, data = self.call("/api/inventory-adjustments", {
            "medicine_id": medicine, "quantity_delta": -1, "at": "2026-02-01T11:05:00"
        })
        self.assertEqual(status, 201, data)
        with self.db.connect() as conn:
            stock = conn.execute("SELECT quantity FROM medicines WHERE id=?", (medicine,)).fetchone()["quantity"]
        self.assertEqual(stock, 1)


if __name__ == "__main__":
    unittest.main()
