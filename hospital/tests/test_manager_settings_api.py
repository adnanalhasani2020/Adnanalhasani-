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


class ManagerSettingsProposalAPITests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "manager-settings.sqlite3")
        self.auth = AuthStore(self.db)
        self.auth.create_user(username="admin.one", password="administrator-password-123",
                              role="admin", at="2026-10-18T08:00:00Z")
        self.auth.create_user(username="admin.two", password="administrator-password-456",
                              role="admin", at="2026-10-18T08:00:00Z")
        self.auth.create_user(username="accountant.user", password="accountant-password-123",
                              role="accountant", at="2026-10-18T08:00:00Z")
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
            try:
                body = json.loads(exc.read())
            except ValueError:
                body = {}
            return exc.code, body

    def login(self, username, password):
        status, payload = self.request("/api/auth/login", "POST",
                                       {"username": username, "password": password})
        self.assertEqual(status, 200)
        return payload["token"]

    def proposal_payload(self):
        return {
            "activity_profile": "outpatient",
            "modules": ["appointments", "billing", "records", "audit"],
            "at": "2026-10-18T10:00:00Z",
        }

    def test_non_admin_cannot_read_or_submit_proposals(self):
        token = self.login("accountant.user", "accountant-password-123")
        status, payload = self.request("/api/manager-settings/proposals", token=token)
        self.assertEqual(status, 403)
        status, payload = self.request("/api/manager-settings/proposals", "POST",
                                       self.proposal_payload(), token)
        self.assertEqual(status, 403)

    def test_proposal_requires_mandatory_audit_module(self):
        token = self.login("admin.one", "administrator-password-123")
        payload = self.proposal_payload()
        payload["modules"] = ["appointments"]
        status, result = self.request("/api/manager-settings/proposals", "POST", payload, token)
        self.assertEqual(status, 400)
        self.assertEqual(result["error"], "modules must be unique and audit is mandatory")

    def test_proposal_persists_and_requires_distinct_reviewer_with_version_check(self):
        author_token = self.login("admin.one", "administrator-password-123")
        reviewer_token = self.login("admin.two", "administrator-password-456")
        status, proposal = self.request("/api/manager-settings/proposals", "POST",
                                        self.proposal_payload(), author_token)
        self.assertEqual(status, 201)
        self.assertEqual(proposal["status"], "proposed")
        self.assertFalse(proposal["activation_supported"])

        status, listing = self.request("/api/manager-settings/proposals", token=reviewer_token)
        self.assertEqual(status, 200)
        self.assertEqual(listing["data"][0]["id"], proposal["id"])
        self.assertEqual(listing["data"][0]["created_by"], "admin.one")

        review = {"decision": "approve", "expected_version": 1,
                  "at": "2026-10-18T10:05:00Z", "note": "Reviewed"}
        status, result = self.request(
            f"/api/manager-settings/proposals/{proposal['id']}/review",
            "POST", review, author_token)
        self.assertEqual(status, 400)
        self.assertIn("cannot review their own", result["error"])

        status, result = self.request(
            f"/api/manager-settings/proposals/{proposal['id']}/review",
            "POST", review, reviewer_token)
        self.assertEqual(status, 200)
        self.assertEqual(result["status"], "approved")
        self.assertFalse(result["activation_supported"])

        status, result = self.request(
            f"/api/manager-settings/proposals/{proposal['id']}/review",
            "POST", review, reviewer_token)
        self.assertEqual(status, 409)

        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT status,version,created_by,reviewed_by FROM manager_setting_proposals WHERE id=?",
                (proposal["id"],),
            ).fetchone()
            audit_count = conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='manager_setting_proposal' AND entity_id=?",
                (str(proposal["id"]),),
            ).fetchone()[0]
        self.assertEqual(row["status"], "approved")
        self.assertEqual(row["version"], 2)
        self.assertEqual(row["created_by"], "admin.one")
        self.assertEqual(row["reviewed_by"], "admin.two")
        self.assertEqual(audit_count, 2)


if __name__ == "__main__":
    unittest.main()
