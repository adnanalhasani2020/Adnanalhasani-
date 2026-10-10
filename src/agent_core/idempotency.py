"""SQLite-backed idempotent execution for application operations.

The caller owns the connection and must not have an open transaction. The
operation callback must perform all effects using that same connection and
return a JSON-serializable result. External side effects are out of scope.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from typing import Any, Callable


class IdempotencyKeyConflict(ValueError):
    """Raised when a key is reused with a different request payload."""


class IdempotencyUsageError(RuntimeError):
    """Raised when the connection cannot safely start an owned transaction."""


def initialize_idempotency(connection: sqlite3.Connection) -> None:
    """Create the durable receipt table; safe to call repeatedly."""
    connection.execute(
        """CREATE TABLE IF NOT EXISTS idempotency_receipts (
            operation_scope TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            request_hash TEXT NOT NULL,
            response_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (operation_scope, idempotency_key)
        )"""
    )


def _request_hash(request: Any) -> str:
    try:
        encoded = json.dumps(request, sort_keys=True, separators=(",", ":"),
                             ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise ValueError("Idempotent request must be valid JSON data") from exc
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def execute_idempotently(connection: sqlite3.Connection, operation_scope: str,
                         idempotency_key: str, request: Any,
                         operation: Callable[[sqlite3.Connection], Any]) -> Any:
    """Execute once per (scope, key); matching retries return the saved result.

    Reusing a key for a different canonical request raises
    IdempotencyKeyConflict. BEGIN IMMEDIATE serializes SQLite writers.
    Exceptions roll back both operation effects and receipt, permitting retry.
    The callback must use this connection and perform no external side effects.
    """
    if not isinstance(operation_scope, str) or not operation_scope.strip():
        raise ValueError("operation_scope must be a non-empty string")
    if not isinstance(idempotency_key, str) or not idempotency_key.strip():
        raise ValueError("idempotency_key must be a non-empty string")
    if connection.in_transaction:
        raise IdempotencyUsageError("connection must have no open transaction")
    fingerprint = _request_hash(request)
    try:
        connection.execute("BEGIN IMMEDIATE")
        row = connection.execute(
            """SELECT request_hash, response_json FROM idempotency_receipts
               WHERE operation_scope=? AND idempotency_key=?""",
            (operation_scope, idempotency_key),
        ).fetchone()
        if row is not None:
            if row[0] != fingerprint:
                raise IdempotencyKeyConflict(
                    "Idempotency key was already used for a different request")
            result = json.loads(row[1])
            connection.commit()
            return result
        result = operation(connection)
        try:
            response_json = json.dumps(result, sort_keys=True, separators=(",", ":"),
                                       ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError) as exc:
            raise ValueError("Idempotent operation result must be valid JSON data") from exc
        connection.execute(
            """INSERT INTO idempotency_receipts
               (operation_scope, idempotency_key, request_hash, response_json)
               VALUES (?, ?, ?, ?)""",
            (operation_scope, idempotency_key, fingerprint, response_json),
        )
        connection.commit()
        return result
    except BaseException:
        if connection.in_transaction:
            connection.rollback()
        raise
