import hashlib
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
from hospital.security import AuthStore


class HospitalUserAuthAPITests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "users-api.sqlite3")
        self.auth = AuthStore(self.db)
        self.auth.create_user(username="admin.user", password="administrator-password-123",
                              role="admin", at="2026-01-01T08:00:00Z")
        self.auth.create_user(username="doctor.user", password="doctor-password-1234",
                              role="doctor", at="2026-01-01T08:00:00Z")
        self.auth.create_user(username="accountant.user", password="accountant-password-123",
                              role="accountant", at="2026-01-01T08:00:00Z")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0),
                                          make_handler(self.db, "", auth_mode="users"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, path, method="GET", payload=None, token=None):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as exc:
            raw = exc.read()
            try:
                body = json.loads(raw)
            except ValueError:
                body = {}
            return exc.code, body

    def login(self, username, password):
        status, payload = self.request("/api/auth/login", "POST",
                                       {"username": username, "password": password})
        self.assertEqual(status, 200)
        return payload["token"]

    def test_login_and_current_user(self):
        token = self.login("doctor.user", "doctor-password-1234")
        status, payload = self.request("/api/auth/me", token=token)
        self.assertEqual(status, 200)
        self.assertEqual(payload["user"]["username"], "doctor.user")
        self.assertEqual(payload["user"]["role"], "doctor")

    def test_bad_login_returns_401(self):
        status, payload = self.request("/api/auth/login", "POST",
                                       {"username": "doctor.user", "password": "wrong-password"})
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")

    def test_doctor_cannot_create_patient_or_manage_users_without_side_effects(self):
        token = self.login("doctor.user", "doctor-password-1234")
        with self.db.connect() as conn:
            patients_before = conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0]
            users_before = conn.execute("SELECT COUNT(*) FROM auth_users").fetchone()[0]
            audit_before = conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]

        status, payload = self.request("/api/patients", "POST", {
            "patient_no": "DENIED-1", "full_name": "Should Not Be Created",
            "created_at": "2026-01-01T08:00:00"
        }, token)
        self.assertEqual(status, 403)
        self.assertEqual(payload["error"], "forbidden")
        status, _ = self.request("/api/users", "POST", {
            "username": "intruder.user", "password": "intruder-password-123",
            "role": "admin", "at": "2026-01-01T08:00:00Z"
        }, token)
        self.assertEqual(status, 403)

        with self.db.connect() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0], patients_before)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM auth_users").fetchone()[0], users_before)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0], audit_before)

    def test_unauthenticated_write_is_rejected_without_database_changes(self):
        with self.db.connect() as conn:
            patients_before = conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0]
            audit_before = conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]

        status, payload = self.request("/api/patients", "POST", {
            "patient_no": "NO-AUTH-1", "full_name": "Must Not Be Created",
            "created_at": "2026-01-01T08:00:00Z"
        })
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")

        with self.db.connect() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0], patients_before)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0], audit_before)

    def test_expired_session_cannot_read_or_write_and_leaves_database_unchanged(self):
        token = self.login("admin.user", "administrator-password-123")
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE auth_sessions SET expires_at=? WHERE token_hash=?",
                ("2000-01-01T00:00:00Z", token_hash),
            )
            patients_before = conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0]
            audit_before = conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]

        status, payload = self.request("/api/patients", "POST", {
            "patient_no": "EXPIRED-SESSION", "full_name": "Must Not Be Created",
            "created_at": "2026-01-01T08:00:00Z"
        }, token)
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")
        status, payload = self.request("/api/patients", token=token)
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")

        with self.db.connect() as conn:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM patients").fetchone()[0], patients_before)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0], audit_before)

    def test_admin_can_create_user_and_user_list_is_protected(self):
        token = self.login("admin.user", "administrator-password-123")
        status, payload = self.request("/api/users", "POST", {
            "username": "reception.user", "password": "reception-password-123",
            "role": "reception", "at": "2026-01-01T08:00:00Z"
        }, token)
        self.assertEqual(status, 201)
        status, users = self.request("/api/users", token=token)
        self.assertEqual(status, 200)
        self.assertTrue(any(row["username"] == "reception.user" for row in users["data"]))

    def test_admission_audit_uses_authenticated_actor(self):
        token = self.login("admin.user", "administrator-password-123")
        status, patient = self.request("/api/patients", "POST", {
            "patient_no": "AUDIT-1", "full_name": "Audit Test Patient",
            "created_at": "2026-01-01T08:00:00Z"
        }, token)
        self.assertEqual(status, 201)
        status, bed = self.request("/api/beds", "POST", {
            "ward": "General", "bed_no": "A-01", "at": "2026-01-01T08:00:00Z"
        }, token)
        self.assertEqual(status, 201)
        status, _ = self.request("/api/admissions", "POST", {
            "patient_id": patient["id"], "bed_id": bed["id"],
            "admitted_at": "2026-01-01T09:00:00Z"
        }, token)
        self.assertEqual(status, 201)
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT actor FROM audit_log WHERE action='patient.admitted' ORDER BY id DESC LIMIT 1"
            ).fetchone()
        self.assertEqual(row["actor"], "admin.user")

    def test_logout_revokes_session(self):
        token = self.login("doctor.user", "doctor-password-1234")
        status, _ = self.request("/api/auth/logout", "POST", {}, token)
        self.assertEqual(status, 200)
        status, payload = self.request("/api/auth/me", token=token)
        self.assertEqual(status, 401)
        self.assertEqual(payload["error"], "unauthorized")


    def test_finance_outbox_status_is_limited_to_accountants_and_admins(self):
        doctor_token = self.login("doctor.user", "doctor-password-1234")
        status, payload = self.request("/api/finance/outbox-status", token=doctor_token)
        self.assertEqual(status, 403)
        self.assertEqual(payload["error"], "forbidden")
        status, payload = self.request("/api/finance/integration-readiness", token=doctor_token)
        self.assertEqual(status, 403)
        self.assertEqual(payload["error"], "forbidden")

        accountant_token = self.login("accountant.user", "accountant-password-123")
        status, payload = self.request("/api/finance/outbox-status", token=accountant_token)
        self.assertEqual(status, 200)
        self.assertEqual(payload["data"]["scope"], "hospital_outbox_transport_only")


if __name__ == "__main__":
    unittest.main()
