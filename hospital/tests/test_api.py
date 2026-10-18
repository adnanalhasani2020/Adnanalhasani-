import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from hospital.api import _validate_bind_host, make_handler
from hospital.core import HospitalDB


class HospitalAPITests(unittest.TestCase):
    def test_bind_host_defaults_to_loopback_only(self):
        for host in ("127.0.0.1", "::1", "localhost"):
            self.assertEqual(_validate_bind_host(host), host)
        for host in ("0.0.0.0", "::", "192.0.2.10", "hospital.example"):
            with self.subTest(host=host):
                with self.assertRaisesRegex(ValueError, "loopback address"):
                    _validate_bind_host(host)

    def test_remote_bind_requires_explicit_opt_in(self):
        self.assertEqual(
            _validate_bind_host("0.0.0.0", allow_remote=True), "0.0.0.0"
        )

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "api.sqlite3")
        self.token = "test-token-that-is-long-enough-for-api-testing-123456"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.db, self.token))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, path, method="GET", payload=None, token=True):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None if payload is None else json.dumps(payload).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_root_serves_arabic_ui_without_api_token(self):
        with urlopen(self.base + "/", timeout=3) as response:
            page = response.read().decode("utf-8")
            self.assertEqual(response.status, 200)
            self.assertIn("text/html", response.headers["Content-Type"])
            self.assertIn("نظام إدارة المستشفى", page)
            self.assertEqual(response.headers["X-Frame-Options"], "DENY")
            self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
            self.assertEqual(response.headers["Referrer-Policy"], "no-referrer")
            self.assertIn("frame-ancestors 'none'", response.headers["Content-Security-Policy"])
            self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_manager_settings_preview_serves_without_api_token(self):
        with urlopen(self.base + "/settings", timeout=3) as response:
            page = response.read().decode("utf-8")
            self.assertEqual(response.status, 200)
            self.assertIn("text/html", response.headers["Content-Type"])
            self.assertIn("إعدادات المدير", page)
            self.assertIn("مستشفى عام", page)
            self.assertIn("sessionStorage", page)
            self.assertIn("لم تُرسل أي تغييرات إلى الخادم", page)
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertEqual(response.headers["X-Frame-Options"], "DENY")
            self.assertIn("frame-ancestors 'none'", response.headers["Content-Security-Policy"])

    def test_authentication_required(self):
        status, payload = self.request("/api/dashboard", token=False)
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")
        # JSON responses must not be cacheable or embeddable in another origin.
        req = Request(self.base + "/api/dashboard",
                      headers={"Authorization": f"Bearer {self.token}"})
        with urlopen(req, timeout=3) as response:
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertEqual(response.headers["X-Frame-Options"], "DENY")

    def test_empty_configured_api_token_fails_closed(self):
        # The API factory must refuse to start with a missing/empty secret.
        with self.assertRaisesRegex(ValueError, "HOSPITAL_API_TOKEN must be set"):
            make_handler(self.db, "")

    def test_global_search_finds_records_across_modules_without_private_fields(self):
        self.request("/api/patients", method="POST", payload={
            "patient_no": "GLOBAL-P-1", "full_name": "Global Search Patient",
            "created_at": "2026-01-01T08:00:00", "allergies": "private allergy",
            "notes": "private note"
        })
        status, payload = self.request("/api/search?search=Global%20Search%20Patient&limit=10&offset=0")
        self.assertEqual(status, 200)
        patient = next(row for row in payload["data"] if row["record_type"] == "patients")
        self.assertEqual(patient["title"], "Global Search Patient")
        self.assertNotIn("allergies", patient)
        self.assertNotIn("notes", patient)

    def test_global_search_requires_query_and_valid_pagination(self):
        status, payload = self.request("/api/search")
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"], "search_required")
        status, payload = self.request("/api/search?search=patient&limit=101")
        self.assertEqual(status, 400)
        self.assertIn("limit must be", payload["error"])

    def test_audit_log_endpoint_lists_and_searches_audited_operations(self):
        status, created = self.request("/api/patients", method="POST", payload={
            "patient_no": "AUDIT-P-1", "full_name": "Audit Sample",
            "created_at": "2026-01-01T08:00:00"
        })
        self.assertEqual(status, 201)
        status, payload = self.request("/api/audit-log?limit=10&offset=0")
        self.assertEqual(status, 200)
        self.assertGreaterEqual(payload["pagination"]["count"], 1)
        row = next(item for item in payload["data"] if item["entity_id"] == str(created["id"]))
        self.assertEqual(row["action"], "patient.registered")
        self.assertEqual(row["entity_type"], "patient")
        status, filtered = self.request("/api/audit-log?search=patient.registered")
        self.assertEqual(status, 200)
        self.assertTrue(all(item["action"] == "patient.registered" for item in filtered["data"]))

    def test_audit_log_endpoint_validates_pagination(self):
        status, payload = self.request("/api/audit-log?limit=101")
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"], "limit must be 1-100 and offset must be non-negative")

    def test_patient_creation_and_dashboard(self):
        status, payload = self.request("/api/patients", method="POST", payload={
            "patient_no": "API-P-1", "full_name": "Sample Patient",
            "created_at": "2026-01-01T08:00:00"
        })
        self.assertEqual(status, 201)
        self.assertEqual(payload["status"], "created")
        status, dashboard = self.request("/api/dashboard")
        self.assertEqual(status, 200)
        self.assertEqual(dashboard["data"]["active_patients"], 1)

    def test_post_rejects_non_json_content_type(self):
        req = Request(
            self.base + "/api/patients",
            data=b'{"patient_no":"P-3"}',
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "text/plain",
            },
            method="POST",
        )
        with self.assertRaises(HTTPError) as raised:
            urlopen(req, timeout=3)
        self.assertEqual(raised.exception.code, 400)
        payload = json.loads(raised.exception.read())
        self.assertEqual(payload["error"], "Content-Type must be application/json")

    def test_post_accepts_json_content_type_with_charset(self):
        payload = json.dumps({
            "patient_no": "API-CHARSET-1",
            "full_name": "Charset Test Patient",
            "created_at": "2026-01-03T08:00:00",
        }).encode("utf-8")
        req = Request(
            self.base + "/api/patients",
            data=payload,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        with urlopen(req, timeout=3) as response:
            result = json.loads(response.read())
            self.assertEqual(response.status, 201)
            self.assertEqual(result["status"], "created")

    def test_post_rejects_missing_content_type(self):
        req = Request(
            self.base + "/api/patients",
            data=b'{"patient_no":"P-4"}',
            headers={"Authorization": f"Bearer {self.token}"},
            method="POST",
        )
        with self.assertRaises(HTTPError) as raised:
            urlopen(req, timeout=3)
        self.assertEqual(raised.exception.code, 400)
        payload = json.loads(raised.exception.read())
        self.assertEqual(payload["error"], "Content-Type must be application/json")

    def test_missing_required_field_is_rejected(self):
        status, payload = self.request("/api/patients", method="POST",
            payload={"patient_no": "P-2"})
        self.assertEqual(status, 400)
        self.assertIn("missing required fields", payload["error"])

    def test_patient_list_supports_search_and_pagination_without_private_notes(self):
        self.request("/api/patients", method="POST", payload={
            "patient_no": "API-P-1", "full_name": "Alpha Patient",
            "created_at": "2026-01-01T08:00:00", "allergies": "test allergy",
            "notes": "private note"
        })
        self.request("/api/patients", method="POST", payload={
            "patient_no": "API-P-2", "full_name": "Beta Patient",
            "created_at": "2026-01-02T08:00:00"
        })
        status, payload = self.request("/api/patients?search=Alpha&limit=1&offset=0")
        self.assertEqual(status, 200)
        self.assertEqual(payload["pagination"]["count"], 1)
        self.assertEqual(payload["data"][0]["full_name"], "Alpha Patient")
        self.assertNotIn("allergies", payload["data"][0])
        self.assertNotIn("notes", payload["data"][0])

    def test_collection_list_requires_authentication_and_valid_pagination(self):
        status, payload = self.request("/api/patients", token=False)
        self.assertEqual(status, 401)
        status, payload = self.request("/api/patients?limit=101")
        self.assertEqual(status, 400)
        self.assertIn("limit must be", payload["error"])

    def test_encounter_list_supports_search_and_exposes_lifecycle_fields(self):
        patient_id = self.db.register_patient(patient_no="ENC-LIST-1",
            full_name="Encounter List Patient", created_at="2026-01-01T08:00:00", actor="test")
        from hospital.workflows import create_encounter
        encounter_id = create_encounter(self.db, patient_id=patient_id,
            doctor_id=None, opened_at="2026-01-01T09:00:00", actor="test")
        status, payload = self.request("/api/encounters?search=Encounter%20List&limit=10&offset=0")
        self.assertEqual(status, 200)
        self.assertEqual(payload["pagination"]["count"], 1)
        self.assertEqual(payload["data"][0]["id"], encounter_id)
        self.assertEqual(payload["data"][0]["status"], "open")
        self.assertEqual(payload["data"][0]["patient_name"], "Encounter List Patient")

    def test_invoice_list_includes_paid_total(self):
        patient_id = self.db.register_patient(patient_no="BILL-LIST-1",
            full_name="Invoice List Patient", created_at="2026-01-01T08:00:00", actor="test")
        invoice_id = self.db.create_invoice(invoice_no="INV-LIST-1", patient_id=patient_id,
            total_minor=12000, created_at="2026-01-01T08:00:00", actor="test")
        self.db.record_payment(invoice_id=invoice_id, amount_minor=3000, method="cash",
            received_at="2026-01-01T09:00:00", actor="test")
        status, payload = self.request("/api/invoices?search=INV-LIST-1")
        self.assertEqual(status, 200)
        self.assertEqual(payload["data"][0]["paid_minor"], 3000)

    def test_payment_api_accepts_idempotency_key_for_safe_retries(self):
        patient_id = self.db.register_patient(patient_no="P-API-IDEMP", full_name="API Retry Patient",
            created_at="2026-01-01T08:00:00", actor="test")
        invoice_id = self.db.create_invoice(invoice_no="INV-API-IDEMP", patient_id=patient_id,
            total_minor=10000, created_at="2026-01-01T08:30:00", actor="test")
        payload = {"invoice_id": invoice_id, "amount_minor": 2500, "method": "cash",
            "received_at": "2026-01-01T09:00:00", "reference": "api-receipt-1",
            "idempotency_key": "api-session:receipt-1"}
        status_first, first = self.request("/api/payments", method="POST", payload=payload)
        status_retry, retry = self.request("/api/payments", method="POST", payload=payload)
        self.assertEqual(status_first, 201)
        self.assertEqual(status_retry, 201)
        self.assertEqual(retry["id"], first["id"])
        with self.db.connect() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM payments WHERE invoice_id=?",
                (invoice_id,)).fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main()


    def test_finance_outbox_status_is_transport_only_and_excludes_payloads(self):
        patient_id = self.db.register_patient(
            patient_no="OUTBOX-STATUS-P1", full_name="Outbox Status Patient",
            created_at="2026-01-01T08:00:00Z", actor="test",
        )
        invoice_id = self.db.create_invoice(
            invoice_no="INV-OUTBOX-STATUS", patient_id=patient_id, total_minor=1200,
            created_at="2026-01-01T08:01:00Z", actor="test",
        )
        status, payload = self.request("/api/finance/outbox-status")
        self.assertEqual(status, 200)
        data = payload["data"]
        self.assertEqual(data["total"], 1)
        self.assertEqual(data["counts"]["pending"], 1)
        self.assertEqual(data["counts"]["failed"], 0)
        self.assertEqual(data["scope"], "hospital_outbox_transport_only")
        self.assertFalse(data["finance_recognition_confirmed"])
        self.assertNotIn("payload_json", data)
        self.assertNotIn("event_key", data)
