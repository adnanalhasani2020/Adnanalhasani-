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


class HospitalAPITests(unittest.TestCase):
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

    def test_authentication_required(self):
        status, payload = self.request("/api/dashboard", token=False)
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")

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

    def test_missing_required_field_is_rejected(self):
        status, payload = self.request("/api/patients", method="POST",
            payload={"patient_no": "P-2"})
        self.assertEqual(status, 400)
        self.assertIn("missing required fields", payload["error"])


if __name__ == "__main__":
    unittest.main()
