"""Per-user authentication and role-based authorization primitives for the hospital API.

This module is deliberately independent of HTTP routing so security behavior can be
tested without starting the server. Tokens are random, stored only as SHA-256 hashes,
and passwords use PBKDF2-HMAC-SHA256 with a per-user salt.
"""
from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterable

ROLES = frozenset({"admin", "doctor", "nurse", "reception", "lab", "pharmacist", "accountant"})
PBKDF2_ITERATIONS = 310_000
SESSION_TTL_HOURS = 8

# Explicit least-privilege defaults. Unknown routes/permissions are denied.
ROLE_PERMISSIONS = {
    "admin": frozenset({"*"}),
    "doctor": frozenset({
        "dashboard:read", "patients:read", "doctors:read", "appointments:read",
        "encounters:read", "encounters:write", "lab:read", "lab:order",
        "medicines:read", "prescriptions:read", "prescriptions:write",
        "beds:read", "admissions:read", "invoices:read",
    }),
    "nurse": frozenset({
        "dashboard:read", "patients:read", "appointments:read", "encounters:read",
        "encounters:write", "lab:read", "beds:read", "admissions:read",
    }),
    "reception": frozenset({
        "dashboard:read", "patients:read", "patients:write", "doctors:read",
        "appointments:read", "appointments:write", "beds:read", "admissions:read",
        "invoices:read",
    }),
    "lab": frozenset({"dashboard:read", "patients:read", "encounters:read", "lab:read", "lab:write"}),
    "pharmacist": frozenset({
        "dashboard:read", "patients:read", "medicines:read", "medicines:write",
        "prescriptions:read", "prescriptions:dispense",
    }),
    "accountant": frozenset({
        "dashboard:read", "patients:read", "invoices:read", "invoices:write", "payments:write",
        "finance:transport:read",
    }),
}


class AuthenticationError(ValueError):
    """Raised when credentials are missing, invalid, expired, or revoked."""


class AuthorizationError(PermissionError):
    """Raised when an authenticated role lacks a requested permission."""


@dataclass(frozen=True)
class Principal:
    user_id: int
    username: str
    role: str
    session_id: int
    expires_at: str


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds")


def _password_hash(password: str, salt: bytes, iterations: int = PBKDF2_ITERATIONS) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, dklen=32)


class AuthStore:
    """User and session store backed by the hospital SQLite database."""

    def __init__(self, database):
        self.database = database
        with self.database.connect() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS auth_users (
                    id INTEGER PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_salt BLOB NOT NULL,
                    password_hash BLOB NOT NULL,
                    password_iterations INTEGER NOT NULL,
                    role TEXT NOT NULL CHECK(role IN
                      ('admin','doctor','nurse','reception','lab','pharmacist','accountant')),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS auth_sessions (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT
                );
                CREATE INDEX IF NOT EXISTS auth_sessions_user_active
                    ON auth_sessions(user_id, expires_at, revoked_at);
            """)

    def create_user(self, *, username: str, password: str, role: str, at: str,
                    actor: str = "system") -> int:
        username = username.strip()
        if len(username) < 3 or len(username) > 80:
            raise ValueError("username must contain 3-80 characters")
        if role not in ROLES:
            raise ValueError("invalid role")
        if len(password) < 12 or len(password) > 1024:
            raise ValueError("password must contain 12-1024 characters")
        salt = secrets.token_bytes(16)
        digest = _password_hash(password, salt)
        with self.database.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("""INSERT INTO auth_users
                (username,password_salt,password_hash,password_iterations,role,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?)""",
                (username, salt, digest, PBKDF2_ITERATIONS, role, at, at))
            user_id = int(cur.lastrowid)
            # Use the existing append-only operation log for administrative changes.
            self.database._audit(conn, actor, "auth.user_created", "auth_user", user_id, at,
                                 {"username": username, "role": role})
            conn.commit()
            return user_id

    def authenticate(self, *, username: str, password: str, at: str | None = None) -> tuple[str, Principal]:
        if at is None:
            now = _utcnow()
        else:
            try:
                now = datetime.fromisoformat(at.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValueError("invalid authentication timestamp") from exc
            if now.tzinfo is None:
                now = now.replace(tzinfo=timezone.utc)
            now = now.astimezone(timezone.utc)
        at = _timestamp(now)
        with self.database.connect() as conn:
            row = conn.execute("""SELECT id,username,password_salt,password_hash,
                    password_iterations,role,active FROM auth_users WHERE username=? COLLATE NOCASE""",
                (username.strip(),)).fetchone()
            # Do a fixed-cost hash for unknown users to reduce username enumeration timing.
            if row is None:
                _password_hash(password, b"\x00" * 16)
                raise AuthenticationError("invalid credentials")
            candidate = _password_hash(password, bytes(row["password_salt"]), int(row["password_iterations"]))
            if not row["active"] or not hmac.compare_digest(candidate, bytes(row["password_hash"])):
                raise AuthenticationError("invalid credentials")
            raw_token = secrets.token_urlsafe(48)
            token_hash = hashlib.sha256(raw_token.encode("ascii")).hexdigest()
            expires_at = _timestamp(now + timedelta(hours=SESSION_TTL_HOURS))
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("""INSERT INTO auth_sessions(user_id,token_hash,created_at,expires_at)
                VALUES(?,?,?,?)""", (row["id"], token_hash, at, expires_at))
            session_id = int(cur.lastrowid)
            conn.commit()
            return raw_token, Principal(int(row["id"]), row["username"], row["role"], session_id, expires_at)

    def resolve(self, token: str, *, at: str | None = None) -> Principal:
        if not token or len(token) > 512:
            raise AuthenticationError("invalid session")
        now = at or _timestamp(_utcnow())
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with self.database.connect() as conn:
            row = conn.execute("""SELECT u.id AS user_id,u.username,u.role,u.active,
                    s.id AS session_id,s.expires_at,s.revoked_at
                FROM auth_sessions s JOIN auth_users u ON u.id=s.user_id
                WHERE s.token_hash=?""", (token_hash,)).fetchone()
        if row is None or not row["active"] or row["revoked_at"] is not None or row["expires_at"] <= now:
            raise AuthenticationError("invalid or expired session")
        return Principal(int(row["user_id"]), row["username"], row["role"],
                         int(row["session_id"]), row["expires_at"])

    def revoke(self, token: str, *, at: str | None = None) -> None:
        if not token:
            return
        now = at or _timestamp(_utcnow())
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with self.database.connect() as conn:
            conn.execute("UPDATE auth_sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                         (now, token_hash))

    def set_user_active(self, *, user_id: int, active: bool, at: str, actor: str) -> None:
        with self.database.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            target = conn.execute("SELECT role,active FROM auth_users WHERE id=?", (user_id,)).fetchone()
            if target is None:
                raise ValueError("user not found")
            if not active and target["role"] == "admin" and target["active"]:
                active_admins = conn.execute(
                    "SELECT COUNT(*) FROM auth_users WHERE role='admin' AND active=1"
                ).fetchone()[0]
                if active_admins <= 1:
                    raise ValueError("cannot deactivate the last active administrator")
            conn.execute("UPDATE auth_users SET active=?,updated_at=? WHERE id=?",
                         (1 if active else 0, at, user_id))
            if not active:
                conn.execute("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                             (at, user_id))
            self.database._audit(conn, actor, "auth.user_activated" if active else "auth.user_deactivated",
                                 "auth_user", user_id, at)
            conn.commit()


def require_permission(principal: Principal, permission: str) -> None:
    # Unknown routes must be denied even to administrators. The wildcard role is
    # for explicitly registered permissions, not an escape hatch for unmapped APIs.
    if permission == "route:unknown":
        raise AuthorizationError("unknown API route denied")
    if principal.role in ROLE_PERMISSIONS and permission in {"session:read", "session:write"}:
        return
    if principal.role not in ROLE_PERMISSIONS:
        raise AuthorizationError("role has no permissions")
    allowed = ROLE_PERMISSIONS[principal.role]
    if "*" not in allowed and permission not in allowed:
        raise AuthorizationError("permission denied")


def permission_for_route(method: str, path: str) -> str | None:
    """Map only implemented API method/path pairs to permissions.

    Unknown paths, unsupported HTTP methods, and unrecognized action suffixes are
    denied by the caller rather than inheriting a module permission.
    """
    method = method.upper()
    route = path.split("?", 1)[0].rstrip("/") or "/"
    if not route.startswith("/api/"):
        return None

    # Reject unsupported verbs before applying the administrator wildcard.
    if method not in {"GET", "POST"}:
        return "route:unknown"

    if route == "/api/auth/me" and method == "GET":
        return "session:read"
    if route == "/api/auth/logout" and method == "POST":
        return "session:write"
    if route == "/api/users" and method == "POST":
        return "users:manage"
    if route == "/api/users" and method == "GET":
        return "users:read"
    if route in {"/api/finance/outbox-status", "/api/finance/integration-readiness"} and method == "GET":
        return "finance:transport:read"

    if route == "/api/manager-settings/active" and method == "GET":
        return "manager:settings:read"

    manager_collections = {
        "/api/manager-settings/proposals",
        "/api/manager-settings/finance-policy-decisions",
    }
    if route in manager_collections:
        return "manager:settings:read" if method == "GET" else "manager:settings:propose"
    if method == "POST" and re.fullmatch(
        r"/api/manager-settings/(?:proposals|finance-policy-decisions)/[1-9][0-9]*/review",
        route,
    ):
        return "manager:settings:review"

    routes = {
        "/api/dashboard": ("dashboard:read", "dashboard:read"),
        "/api/search": ("records:read", "records:read"),
        "/api/audit-log": ("audit:read", "audit:read"),
        "/api/patients": ("patients:read", "patients:write"),
        "/api/doctors": ("doctors:read", "doctors:write"),
        "/api/appointments": ("appointments:read", "appointments:write"),
        "/api/encounters": ("encounters:read", "encounters:write"),
        "/api/lab-orders": ("lab:read", "lab:order"),
        "/api/medicines": ("medicines:read", "medicines:write"),
        "/api/prescriptions": ("prescriptions:read", "prescriptions:write"),
        "/api/beds": ("beds:read", "beds:write"),
        "/api/admissions": ("admissions:read", "admissions:write"),
        "/api/invoices": ("invoices:read", "invoices:write"),
        "/api/payments": ("payments:read", "payments:write"),
    }
    if route in routes:
        return routes[route][0 if method == "GET" else 1]

    if method == "GET" and re.fullmatch(r"/api/medicines/[1-9][0-9]*/batches", route):
        return "medicines:read"

    # Stock history is read-only and inherits only the medicine read permission.
    if method == "GET" and re.fullmatch(r"/api/medicines/[1-9][0-9]*/stock-movements", route):
        return "medicines:read"

    # Only implemented resource actions are authorized; arbitrary nested paths
    # must not inherit a permission merely because their first segment is known.
    if method == "POST":
        if re.fullmatch(r"/api/appointments/[1-9][0-9]*/(?:cancel|no-show)", route):
            return "appointments:write"
        if re.fullmatch(r"/api/encounters/[1-9][0-9]*/(?:close|diagnosis)", route):
            return "encounters:write"
        if re.fullmatch(r"/api/lab-orders/[1-9][0-9]*/status", route):
            return "lab:write"
        if re.fullmatch(r"/api/medicines/[1-9][0-9]*/restock", route):
            return "medicines:write"
        if re.fullmatch(r"/api/prescriptions/[1-9][0-9]*/dispense", route):
            return "prescriptions:dispense"
        if re.fullmatch(r"/api/admissions/[1-9][0-9]*/discharge", route):
            return "admissions:write"
        if re.fullmatch(r"/api/users/[1-9][0-9]*/active", route):
            return "users:manage"
    return "route:unknown"


def ensure_user_permission(principal: Principal, permission: str) -> None:
    require_permission(principal, permission)
