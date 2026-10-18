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
            "at": "1900-01-01T00:00:00Z",
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
        self.assertTrue(proposal["activation_supported"])

        status, listing = self.request("/api/manager-settings/proposals", token=reviewer_token)
        self.assertEqual(status, 200)
        self.assertEqual(listing["data"][0]["id"], proposal["id"])
        self.assertEqual(listing["data"][0]["created_by"], "admin.one")

        review = {"decision": "approve", "expected_version": 1,
                  "at": "1900-01-01T00:00:00Z", "note": "Reviewed"}
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
        self.assertTrue(result["activation_supported"])

        status, result = self.request(
            f"/api/manager-settings/proposals/{proposal['id']}/review",
            "POST", review, reviewer_token)
        self.assertEqual(status, 409)

        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT status,version,created_by,reviewed_by,created_at,reviewed_at FROM manager_setting_proposals WHERE id=?",
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
        self.assertNotEqual(row["created_at"], "1900-01-01T00:00:00Z")
        self.assertNotEqual(row["reviewed_at"], "1900-01-01T00:00:00Z")
        self.assertEqual(audit_count, 2)


if __name__ == "__main__":
    unittest.main()


    def test_finance_settlement_policy_choice_requires_distinct_review_and_never_activates(self):
        author = self.login("admin.one", "administrator-password-123")
        reviewer = self.login("admin.two", "administrator-password-456")
        status, proposal = self.request(
            "/api/manager-settings/finance-policy-decisions", "POST",
            {"policy_key": "remaining_balance", "rationale": "Our clinic receives deposits and installments."},
            author,
        )
        self.assertEqual(status, 201)
        self.assertEqual(proposal["status"], "proposed")
        self.assertFalse(proposal["activation_supported"])

        status, own_review = self.request(
            f"/api/manager-settings/finance-policy-decisions/{proposal['id']}/review", "POST",
            {"decision": "approve", "expected_version": 1}, author,
        )
        self.assertEqual(status, 400)
        self.assertIn("cannot review their own", own_review["error"])

        status, rejected_without_reason = self.request(
            f"/api/manager-settings/finance-policy-decisions/{proposal['id']}/review", "POST",
            {"decision": "reject", "expected_version": 1, "note": " "}, reviewer,
        )
        self.assertEqual(status, 400)
        self.assertIn("reason is required", rejected_without_reason["error"])

        status, approved = self.request(
            f"/api/manager-settings/finance-policy-decisions/{proposal['id']}/review", "POST",
            {"decision": "approve", "expected_version": 1, "note": "Choice accepted for planning."},
            reviewer,
        )
        self.assertEqual(status, 200)
        self.assertEqual(approved["status"], "approved")
        self.assertFalse(approved["activation_supported"])
        status, listing = self.request("/api/manager-settings/finance-policy-decisions", token=reviewer)
        self.assertEqual(status, 200)
        self.assertEqual(listing["data"][0]["policy_key"], "remaining_balance")
        self.assertEqual(listing["data"][0]["reviewed_by"], "admin.two")
        with self.db.connect() as conn:
            audit = conn.execute(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type='manager_finance_policy_decision' AND entity_id=?",
                (str(proposal["id"]),),
            ).fetchone()[0]
        self.assertEqual(audit, 2)

    def test_finance_settlement_policy_rejects_invalid_choices_and_preserves_history(self):
        import sqlite3
        author = self.login("admin.one", "administrator-password-123")
        status, invalid = self.request(
            "/api/manager-settings/finance-policy-decisions", "POST",
            {"policy_key": "auto_post_partial", "rationale": "not supported"},
            author,
        )
        self.assertEqual(status, 400)
        self.assertIn("unsupported partial-settlement policy", invalid["error"])
        status, proposal = self.request(
            "/api/manager-settings/finance-policy-decisions", "POST",
            {"policy_key": "multiple_allocations", "rationale": "Track each payment allocation."},
            author,
        )
        self.assertEqual(status, 201)
        with self.db.connect() as conn:
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("DELETE FROM manager_finance_policy_decisions WHERE id=?", (proposal["id"],))
            count = conn.execute("SELECT COUNT(*) FROM manager_finance_policy_decisions").fetchone()[0]
        self.assertEqual(count, 1)
