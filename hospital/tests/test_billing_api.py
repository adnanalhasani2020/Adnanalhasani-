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


class BillingAPIWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "billing-api.sqlite3")
        self.token = "billing-api-test-token-long-enough-123456789"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.db, self.token))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.patient = self.post("/api/patients", {
            "patient_no": "BILL-P-1", "full_name": "Billing Test Patient",
            "created_at": "2026-01-01T08:00:00"
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

    def test_invoice_payment_lifecycle_and_balance_limit(self):
        invoice = self.post("/api/invoices", {
            "invoice_no": "INV-API-1", "patient_id": self.patient,
            "total_minor": 10000, "currency": "YER",
            "created_at": "2026-01-02T08:00:00"
        })["id"]
        payment = self.post("/api/payments", {
            "invoice_id": invoice, "amount_minor": 4000, "method": "cash",
            "received_at": "2026-01-02T09:00:00", "reference": "RCPT-1"
        })
        self.assertEqual(payment["status"], "received")
        with self.db.connect() as conn:
            row = conn.execute("SELECT status FROM invoices WHERE id=?", (invoice,)).fetchone()
        self.assertEqual(row["status"], "partial")

        self.post("/api/payments", {
            "invoice_id": invoice, "amount_minor": 6000, "method": "cash",
            "received_at": "2026-01-02T10:00:00"
        })
        with self.db.connect() as conn:
            row = conn.execute("SELECT status FROM invoices WHERE id=?", (invoice,)).fetchone()
        self.assertEqual(row["status"], "paid")

        status, data = self.call("/api/payments", {
            "invoice_id": invoice, "amount_minor": 1, "method": "cash",
            "received_at": "2026-01-02T11:00:00"
        })
        self.assertEqual(status, 400)
        self.assertIn("exceeds invoice balance", data["error"])

    def test_invoice_rejects_encounter_belonging_to_another_patient_atomically(self):
        other = self.post("/api/patients", {
            "patient_no": "BILL-P-OTHER", "full_name": "Other Billing Patient",
            "created_at": "2026-01-01T08:00:00"
        })["id"]
        encounter = self.post("/api/encounters", {
            "patient_id": other, "opened_at": "2026-01-02T08:00:00",
            "chief_complaint": "Synthetic test"
        })["id"]
        with self.db.connect() as conn:
            before_audit = conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE action='invoice.created'"
            ).fetchone()[0]
            before_outbox = conn.execute(
                "SELECT COUNT(*) FROM finance_outbox WHERE event_type='hospital.invoice.created'"
            ).fetchone()[0]

        status, data = self.call("/api/invoices", {
            "invoice_no": "INV-MISMATCH", "patient_id": self.patient,
            "encounter_id": encounter, "total_minor": 1000,
            "created_at": "2026-01-02T09:00:00"
        })
        self.assertEqual(status, 400)
        self.assertIn("encounter does not belong to this patient", data["error"])
        with self.db.connect() as conn:
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM invoices WHERE invoice_no='INV-MISMATCH'"
            ).fetchone()[0], 0)
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE action='invoice.created'"
            ).fetchone()[0], before_audit)
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM finance_outbox WHERE event_type='hospital.invoice.created'"
            ).fetchone()[0], before_outbox)

    def test_invoice_requires_valid_amount_and_patient(self):
        status, data = self.call("/api/invoices", {
            "invoice_no": "INV-API-2", "patient_id": self.patient,
            "total_minor": -1, "created_at": "2026-01-02T08:00:00"
        })
        self.assertEqual(status, 400)


if __name__ == "__main__":
    unittest.main()
