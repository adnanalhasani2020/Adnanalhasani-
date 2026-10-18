import tempfile
import unittest
from pathlib import Path

from hospital.core import HospitalDB
from hospital.security import (
    AuthStore, AuthenticationError, AuthorizationError, Principal,
    permission_for_route, require_permission,
)


class HospitalSecurityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = HospitalDB(Path(self.temp.name) / "security.sqlite3")
        self.auth = AuthStore(self.db)

    def tearDown(self):
        self.temp.cleanup()

    def test_passwords_are_not_stored_as_plaintext_and_login_returns_session(self):
        self.auth.create_user(username="doctor.one", password="a-strong-password-123",
                              role="doctor", at="2026-01-01T08:00:00Z")
        with self.db.connect() as conn:
            row = conn.execute("SELECT password_hash,password_salt FROM auth_users WHERE username=?",
                               ("doctor.one",)).fetchone()
        self.assertNotEqual(bytes(row["password_hash"]), b"a-strong-password-123")
        self.assertEqual(len(bytes(row["password_salt"])), 16)
        token, principal = self.auth.authenticate(username="DOCTOR.ONE",
            password="a-strong-password-123", at="2026-01-01T08:00:00+00:00")
        self.assertGreaterEqual(len(token), 50)
        self.assertEqual(principal.role, "doctor")
        resolved = self.auth.resolve(token, at="2026-01-01T08:01:00+00:00")
        self.assertEqual(resolved.user_id, principal.user_id)

    def test_invalid_credentials_do_not_authenticate(self):
        self.auth.create_user(username="nurse.one", password="another-strong-password",
                              role="nurse", at="2026-01-01T08:00:00Z")
        for username, password in (("nurse.one", "wrong-password-123"),
                                   ("missing.user", "wrong-password-123")):
            with self.subTest(username=username):
                with self.assertRaises(AuthenticationError):
                    self.auth.authenticate(username=username, password=password,
                                           at="2026-01-01T08:00:00Z")

    def test_sessions_expire_and_can_be_revoked(self):
        token, _ = self._create_and_login()
        with self.assertRaises(AuthenticationError):
            self.auth.resolve(token, at="2026-01-01T17:00:01+00:00")
        self.auth.revoke(token, at="2026-01-01T08:01:00+00:00")
        with self.assertRaises(AuthenticationError):
            self.auth.resolve(token, at="2026-01-01T08:02:00+00:00")

    def test_deactivating_user_revokes_existing_sessions(self):
        token, principal = self._create_and_login()
        self.auth.set_user_active(user_id=principal.user_id, active=False,
                                  at="2026-01-01T08:01:00Z", actor="admin")
        with self.assertRaises(AuthenticationError):
            self.auth.resolve(token, at="2026-01-01T08:02:00Z")

    def test_role_permissions_follow_least_privilege(self):
        doctor = Principal(1, "doc", "doctor", 1, "2026-01-01T16:00:00Z")
        pharmacist = Principal(2, "pharm", "pharmacist", 2, "2026-01-01T16:00:00Z")
        require_permission(doctor, "encounters:write")
        with self.assertRaises(AuthorizationError):
            require_permission(doctor, "users:manage")
        require_permission(pharmacist, "prescriptions:dispense")
        with self.assertRaises(AuthorizationError):
            require_permission(pharmacist, "payments:write")

    def test_route_mapping_is_explicit_for_sensitive_operations(self):
        self.assertEqual(permission_for_route("POST", "/api/users"), "users:manage")
        self.assertEqual(permission_for_route("POST", "/api/payments"), "payments:write")
        self.assertEqual(permission_for_route("GET", "/api/audit-log"), "audit:read")
        self.assertEqual(permission_for_route("POST", "/api/prescriptions/7/dispense"),
                         "prescriptions:dispense")
        self.assertIsNone(permission_for_route("GET", "/settings"))
        self.assertEqual(permission_for_route("GET", "/api/future-sensitive-route"), "route:unknown")

    def test_unrecognized_actions_and_http_methods_do_not_inherit_permissions(self):
        denied_routes = (
            ("POST", "/api/prescriptions/7/refill"),
            ("POST", "/api/admissions/7/transfer/extra"),
            ("POST", "/api/manager-settings/proposals/not-a-number/review"),
            ("DELETE", "/api/patients"),
            ("PATCH", "/api/payments"),
            ("POST", "/api/users/7/password-reset"),
        )
        for method, path in denied_routes:
            with self.subTest(method=method, path=path):
                self.assertEqual(permission_for_route(method, path), "route:unknown")

    def test_implemented_action_routes_keep_explicit_permissions(self):
        self.assertEqual(permission_for_route("POST", "/api/appointments/7/cancel"), "appointments:write")
        self.assertEqual(permission_for_route("POST", "/api/appointments/7/no-show"), "appointments:write")
        self.assertEqual(permission_for_route("POST", "/api/appointments/7/delete"), "route:unknown")
        self.assertEqual(permission_for_route("POST", "/api/encounters/7/close"), "encounters:write")
        self.assertEqual(permission_for_route("POST", "/api/lab-orders/7/status"), "lab:write")
        self.assertEqual(permission_for_route("POST", "/api/admissions/7/discharge"), "admissions:write")
        self.assertEqual(permission_for_route("POST", "/api/users/7/active"), "users:manage")
        self.assertEqual(permission_for_route("POST", "/api/manager-settings/proposals/7/review"),
                         "manager:settings:review")

    def test_unknown_api_routes_are_denied_even_to_administrators(self):
        admin = Principal(1, "admin", "admin", 1, "2026-01-01T16:00:00Z")
        permission = permission_for_route("GET", "/api/future-sensitive-route")
        self.assertEqual(permission, "route:unknown")
        with self.assertRaisesRegex(AuthorizationError, "unknown API route denied"):
            require_permission(admin, permission)

    def test_last_active_administrator_cannot_be_deactivated(self):
        user_id = self.auth.create_user(username="only.admin", password="a-strong-password-123",
                                        role="admin", at="2026-01-01T08:00:00Z")
        with self.assertRaisesRegex(ValueError, "last active administrator"):
            self.auth.set_user_active(user_id=user_id, active=False,
                                      at="2026-01-01T09:00:00Z", actor="only.admin")
        with self.db.connect() as conn:
            row = conn.execute("SELECT active FROM auth_users WHERE id=?", (user_id,)).fetchone()
        self.assertEqual(row["active"], 1)

    def test_invalid_user_inputs_are_rejected(self):
        with self.assertRaises(ValueError):
            self.auth.create_user(username="ab", password="a-strong-password-123",
                                  role="doctor", at="2026-01-01T08:00:00Z")
        with self.assertRaises(ValueError):
            self.auth.create_user(username="goodname", password="short",
                                  role="doctor", at="2026-01-01T08:00:00Z")
        with self.assertRaises(ValueError):
            self.auth.create_user(username="goodname", password="a-strong-password-123",
                                  role="superuser", at="2026-01-01T08:00:00Z")

    def _create_and_login(self):
        self.auth.create_user(username="doctor.one", password="a-strong-password-123",
                              role="doctor", at="2026-01-01T08:00:00Z")
        return self.auth.authenticate(username="doctor.one", password="a-strong-password-123",
                                      at="2026-01-01T08:00:00+00:00")


if __name__ == "__main__":
    unittest.main()
