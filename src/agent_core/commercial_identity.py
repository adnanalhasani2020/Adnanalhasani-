"""Credential and session boundary for the first commercial MVP."""
from __future__ import annotations

import hashlib
import hmac
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from agent_core.shared import ValidationError


def _now(value=None) -> datetime:
    if value is None:
        value = datetime.now(timezone.utc)
    elif isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError("Timestamp must be valid ISO-8601") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError("Timestamp must be timezone-aware")
    return value.astimezone(timezone.utc)


def _stamp(value: datetime) -> str:
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def _username(value: str) -> str:
    if not isinstance(value, str):
        raise ValidationError("Username is required")
    result = value.strip().lower()
    if not 3 <= len(result) <= 80 or not all(ch.isascii() and (ch.isalnum() or ch in "._-") for ch in result):
        raise ValidationError("Username must be 3–80 characters using letters, numbers, dot, underscore, or hyphen")
    return result


def _password(value: str) -> str:
    if not isinstance(value, str) or len(value) < 12 or len(value) > 1024:
        raise ValidationError("Password must be between 12 and 1024 characters")
    return value


def _password_hash(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)


class CommercialIdentityApplication:
    """Register local credentials, authenticate them, and issue revocable sessions.

    This is a local first-release authentication boundary, not an external identity
    provider or production SSO implementation.
    """

    SESSION_LIFETIME = timedelta(hours=8)
    LOGIN_WINDOW = timedelta(minutes=15)
    MAX_LOGIN_ATTEMPTS = 5

    def register(self, connection: sqlite3.Connection, *, username, password, now=None) -> dict:
        name = _username(username)
        secret = _password(password)
        instant = _now(now)
        stamp = _stamp(instant)
        salt = secrets.token_bytes(16)
        digest = _password_hash(secret, salt).hex()
        person_id, account_id, credential_id = str(uuid4()), str(uuid4()), str(uuid4())
        try:
            if connection.in_transaction:
                raise ValidationError("Registration requires an unowned transaction")
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """INSERT INTO persons(person_id,state,canonical_person_id,created_at,updated_at,version_no)
                   VALUES(?,'active',NULL,?,?,1)""",
                (person_id, stamp, stamp),
            )
            connection.execute(
                """INSERT INTO access_accounts(access_account_id,person_id,state,created_at,updated_at,version_no)
                   VALUES(?,?,'active',?,?,1)""",
                (account_id, person_id, stamp, stamp),
            )
            connection.execute(
                """INSERT INTO commercial_credentials(
                       credential_id,person_id,access_account_id,username,password_salt,
                       password_hash,state,created_at,updated_at
                   ) VALUES(?,?,?,?,?,?,'active',?,?)""",
                (credential_id, person_id, account_id, name, salt.hex(), digest, stamp, stamp),
            )
            connection.commit()
        except sqlite3.IntegrityError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise ValidationError("Username is already registered or registration violates the account schema") from exc
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
        return {"person_id": person_id, "username": name}

    def login(self, connection: sqlite3.Connection, *, username, password, now=None) -> dict:
        name = _username(username)
        secret = _password(password)
        instant = _now(now)
        stamp = _stamp(instant)
        window_start = _stamp(instant - self.LOGIN_WINDOW)
        if connection.in_transaction:
            raise ValidationError("Login requires an unowned transaction")
        connection.execute("BEGIN IMMEDIATE")
        try:
            attempt = connection.execute(
                "SELECT window_started_at,attempt_count FROM commercial_login_attempts WHERE username_key=?",
                (name,),
            ).fetchone()
            if attempt is not None:
                prior_start = _now(attempt[0])
                if prior_start > instant - self.LOGIN_WINDOW and attempt[1] >= self.MAX_LOGIN_ATTEMPTS:
                    raise ValidationError("Too many login attempts; try again after the rate-limit window")
                if prior_start <= instant - self.LOGIN_WINDOW:
                    connection.execute(
                        "UPDATE commercial_login_attempts SET window_started_at=?,attempt_count=0,updated_at=? WHERE username_key=?",
                        (stamp, stamp, name),
                    )

            row = connection.execute(
                """SELECT c.person_id,c.password_salt,c.password_hash,c.state,p.state,a.state
                   FROM commercial_credentials c
                   JOIN persons p ON p.person_id=c.person_id
                   JOIN access_accounts a ON a.access_account_id=c.access_account_id
                   WHERE c.username=?""",
                (name,),
            ).fetchone()
            # Perform the same KDF on unknown usernames to reduce timing-based enumeration.
            salt = bytes.fromhex(row[1]) if row is not None else bytes(16)
            expected = bytes.fromhex(row[2]) if row is not None else _password_hash("not-the-password", salt)
            supplied = _password_hash(secret, salt)
            valid = row is not None and hmac.compare_digest(supplied, expected)
            if not valid or row[3] != "active" or row[4] != "active" or row[5] != "active":
                connection.execute(
                    """INSERT INTO commercial_login_attempts(username_key,window_started_at,attempt_count,updated_at)
                       VALUES(?,?,1,?)
                       ON CONFLICT(username_key) DO UPDATE SET
                         attempt_count=CASE
                           WHEN commercial_login_attempts.window_started_at<=? THEN 1
                           ELSE commercial_login_attempts.attempt_count+1 END,
                         window_started_at=CASE
                           WHEN commercial_login_attempts.window_started_at<=? THEN excluded.window_started_at
                           ELSE commercial_login_attempts.window_started_at END,
                         updated_at=excluded.updated_at""",
                    (name, stamp, stamp, window_start, window_start),
                )
                connection.commit()
                raise ValidationError("Invalid username or password")

            person_id = row[0]
            token = secrets.token_urlsafe(32)
            expires = _stamp(instant + self.SESSION_LIFETIME)
            connection.execute(
                """INSERT INTO commercial_sessions(session_id,person_id,token_hash,created_at,expires_at,revoked_at)
                   VALUES(?,?,?,?,?,NULL)""",
                (str(uuid4()), person_id, hashlib.sha256(token.encode("utf-8")).hexdigest(), stamp, expires),
            )
            connection.execute(
                """INSERT INTO commercial_login_attempts(username_key,window_started_at,attempt_count,updated_at)
                   VALUES(?,?,0,?)
                   ON CONFLICT(username_key) DO UPDATE SET
                     window_started_at=excluded.window_started_at,attempt_count=0,updated_at=excluded.updated_at""",
                (name, stamp, stamp),
            )
            connection.commit()
            return {"person_id": person_id, "username": name, "session_token": token, "expires_at": expires}
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise

    def login_external(self, connection: sqlite3.Connection, *, provider_user_id: str,
                       email: str, now=None) -> dict:
        """Create/reuse a local commerce profile after the external provider authenticated the user."""
        if not isinstance(provider_user_id, str) or not provider_user_id.strip() or len(provider_user_id) > 128:
            raise ValidationError("External identity is invalid")
        if not isinstance(email, str) or not email.strip() or len(email) > 320 or "@" not in email:
            raise ValidationError("External identity email is invalid")
        provider_user_id = provider_user_id.strip()
        email = email.strip().lower()
        instant = _now(now)
        stamp = _stamp(instant)
        if connection.in_transaction:
            raise ValidationError("External login requires an unowned transaction")
        connection.execute("BEGIN IMMEDIATE")
        try:
            row = connection.execute(
                """SELECT i.person_id,p.state,a.state,c.state
                   FROM commercial_supabase_identities i
                   JOIN persons p ON p.person_id=i.person_id
                   JOIN access_accounts a ON a.person_id=i.person_id
                   JOIN commercial_credentials c ON c.person_id=i.person_id
                   WHERE i.supabase_user_id=?""",
                (provider_user_id,),
            ).fetchone()
            if row is None:
                person_id, account_id, credential_id = str(uuid4()), str(uuid4()), str(uuid4())
                username = "sb_" + hashlib.sha256(provider_user_id.encode("utf-8")).hexdigest()[:32]
                salt = secrets.token_bytes(16)
                # This random local credential is never returned or used for authentication.
                digest = _password_hash(secrets.token_urlsafe(32), salt).hex()
                connection.execute(
                    """INSERT INTO persons(person_id,state,canonical_person_id,created_at,updated_at,version_no)
                       VALUES(?,'active',NULL,?,?,1)""", (person_id, stamp, stamp),
                )
                connection.execute(
                    """INSERT INTO access_accounts(access_account_id,person_id,state,created_at,updated_at,version_no)
                       VALUES(?,?,'active',?,?,1)""", (account_id, person_id, stamp, stamp),
                )
                connection.execute(
                    """INSERT INTO commercial_credentials(
                           credential_id,person_id,access_account_id,username,password_salt,
                           password_hash,state,created_at,updated_at
                       ) VALUES(?,?,?,?,?,?,'active',?,?)""",
                    (credential_id, person_id, account_id, username, salt.hex(), digest, stamp, stamp),
                )
                connection.execute(
                    """INSERT INTO commercial_supabase_identities(
                           supabase_user_id,person_id,email,created_at,updated_at
                       ) VALUES(?,?,?,?,?)""",
                    (provider_user_id, person_id, email, stamp, stamp),
                )
            else:
                person_id = row[0]
                if row[1:] != ("active", "active", "active"):
                    raise ValidationError("الحساب التجاري موقوف؛ تواصل مع المسؤول.")
                connection.execute(
                    "UPDATE commercial_supabase_identities SET email=?,updated_at=? WHERE supabase_user_id=?",
                    (email, stamp, provider_user_id),
                )
                username = "sb_" + hashlib.sha256(provider_user_id.encode("utf-8")).hexdigest()[:32]

            token = secrets.token_urlsafe(32)
            expires = _stamp(instant + self.SESSION_LIFETIME)
            connection.execute(
                """INSERT INTO commercial_sessions(session_id,person_id,token_hash,created_at,expires_at,revoked_at)
                   VALUES(?,?,?,?,?,NULL)""",
                (str(uuid4()), person_id, hashlib.sha256(token.encode("utf-8")).hexdigest(), stamp, expires),
            )
            connection.commit()
            return {"person_id": person_id, "username": email, "session_token": token, "expires_at": expires}
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise

    def resolve_session(self, connection: sqlite3.Connection, token, *, now=None) -> str | None:
        if not isinstance(token, str) or not token:
            return None
        instant = _now(now)
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        rows = connection.execute(
            """SELECT s.person_id,s.expires_at,p.state,a.state,c.state
               FROM commercial_sessions s
               JOIN persons p ON p.person_id=s.person_id
               JOIN access_accounts a ON a.person_id=s.person_id
               JOIN commercial_credentials c ON c.person_id=s.person_id
               WHERE s.token_hash=? AND s.revoked_at IS NULL""",
            (token_hash,),
        ).fetchall()
        if len(rows) != 1:
            return None
        person_id, expires_raw, person_state, account_state, credential_state = rows[0]
        if _now(expires_raw) <= instant or person_state != "active" or account_state != "active" or credential_state != "active":
            return None
        return person_id

    def logout(self, connection: sqlite3.Connection, token, *, now=None) -> bool:
        if not isinstance(token, str) or not token:
            return False
        stamp = _stamp(_now(now))
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        owns_transaction = not connection.in_transaction
        savepoint = "commercial_logout_" + str(uuid4()).replace("-", "")
        if owns_transaction:
            connection.execute("BEGIN IMMEDIATE")
        else:
            connection.execute(f"SAVEPOINT {savepoint}")
        try:
            connection.execute(
                "UPDATE commercial_sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                (stamp, token_hash),
            )
            changed = connection.execute("SELECT changes()").fetchone()[0] == 1
            if owns_transaction:
                connection.commit()
            else:
                connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            return changed
        except BaseException:
            if connection.in_transaction:
                if owns_transaction:
                    connection.rollback()
                else:
                    connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
                    connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            raise
