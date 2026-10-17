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


class ClinicalAPIWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "clinical-api.sqlite3")
        self.token = "clinical-api-test-token-long-enough-123456789"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.db, self.token))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.patient = self.post("/api/patients", {
            "patient_no": "P-1", "full_name": "Sample Patient", "created_at": "2026-01-01T08:00:00"
        })["id"]
        self.doctor = self.post("/api/doctors", {
            "employee_no": "D-1", "full_name": "Sample Doctor", "specialty": "General",
            "at": "2026-01-01T08:00:00"
        })["id"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def call(self, path, payload):
        req = Request(self.base + path, data=json.dumps(payload).encode(),
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
            method="POST")
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

    def test_encounter_and_lab_result_lifecycle(self):
        encounter = self.post("/api/encounters", {
            "patient_id": self.patient, "doctor_id": self.doctor,
            "opened_at": "2026-01-02T08:00:00", "chief_complaint": "test"
        })["id"]
        order = self.post("/api/lab-orders", {
            "patient_id": self.patient, "doctor_id": self.doctor,
            "encounter_id": encounter, "test_name": "CBC", "ordered_at": "2026-01-02T08:01:00"
        })["id"]
        for lab_status, at in (("collected", "2026-01-02T08:10:00"),
                               ("processing", "2026-01-02T08:30:00")):
            status, data = self.call(f"/api/lab-orders/{order}/status", {
                "status": lab_status, "at": at
            })
            self.assertEqual(status, 200, data)
        status, data = self.call(f"/api/lab-orders/{order}/status", {
            "status": "resulted", "at": "2026-01-02T09:00:00", "result": "Test result"
        })
        self.assertEqual(status, 200, data)
        status, data = self.call(f"/api/encounters/{encounter}/close", {
            "closed_at": "2026-01-02T09:10:00", "diagnosis": "Test diagnosis"
        })
        self.assertEqual(status, 200, data)

    def test_pharmacy_dispense_deducts_stock(self):
        medicine = self.post("/api/medicines", {
            "sku": "MED-1", "name": "Sample medicine", "quantity": 8,
            "reorder_level": 1, "at": "2026-01-01T08:00:00"
        })["id"]
        prescription = self.post("/api/prescriptions", {
            "patient_id": self.patient, "medicine_id": medicine, "dose": "1 tablet",
            "frequency": "daily", "duration": "3 days", "quantity": 3,
            "prescribed_at": "2026-01-02T08:00:00"
        })["id"]
        status, data = self.call(f"/api/prescriptions/{prescription}/dispense", {
            "dispensed_at": "2026-01-02T09:00:00"
        })
        self.assertEqual(status, 200, data)
        with self.db.connect() as conn:
            stock = conn.execute("SELECT quantity FROM medicines WHERE id=?", (medicine,)).fetchone()["quantity"]
        self.assertEqual(stock, 5)

    def test_admission_bed_occupancy_and_discharge(self):
        bed = self.post("/api/beds", {
            "ward": "A", "bed_no": "01", "at": "2026-01-01T08:00:00"
        })["id"]
        admission = self.post("/api/admissions", {
            "patient_id": self.patient, "bed_id": bed, "admitted_at": "2026-01-02T08:00:00"
        })["id"]
        status, data = self.call("/api/admissions", {
            "patient_id": self.patient, "bed_id": bed, "admitted_at": "2026-01-02T09:00:00"
        })
        self.assertEqual(status, 400)
        status, data = self.call(f"/api/admissions/{admission}/discharge", {
            "discharged_at": "2026-01-03T08:00:00"
        })
        self.assertEqual(status, 200, data)


if __name__ == "__main__":
    unittest.main()
