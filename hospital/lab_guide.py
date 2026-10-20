"""Versioned laboratory specimen-collection guide for the hospital SQLite foundation.

This module stores operational guidance only. It does not supply or validate clinical
instructions; entries must be sourced and approved by the facility's laboratory lead.
Callers must authenticate and authorize actors before invoking these functions.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any

from .core import HospitalDB, HospitalError


_SCHEMA = """
CREATE TABLE IF NOT EXISTS lab_specimen_guide (
    id INTEGER PRIMARY KEY,
    test_code TEXT NOT NULL UNIQUE,
    test_name TEXT NOT NULL,
    specimen_type TEXT NOT NULL,
    container TEXT NOT NULL,
    preparation TEXT NOT NULL DEFAULT '',
    collection_instructions TEXT NOT NULL DEFAULT '',
    transport_conditions TEXT NOT NULL DEFAULT '',
    stability TEXT NOT NULL DEFAULT '',
    rejection_criteria TEXT NOT NULL DEFAULT '',
    expected_turnaround TEXT NOT NULL DEFAULT '',
    source_reference TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'draft'
        CHECK(status IN ('draft','approved','retired')),
    version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
    created_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_by TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reviewed_by TEXT,
    reviewed_at TEXT,
    CHECK((status='approved' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
           AND length(trim(source_reference)) > 0)
       OR status IN ('draft','retired'))
);
CREATE INDEX IF NOT EXISTS lab_specimen_guide_name_status
    ON lab_specimen_guide(status, test_name);
CREATE TABLE IF NOT EXISTS lab_specimen_guide_history (
    id INTEGER PRIMARY KEY,
    guide_id INTEGER NOT NULL REFERENCES lab_specimen_guide(id),
    version INTEGER NOT NULL CHECK(version >= 1),
    action TEXT NOT NULL CHECK(action IN ('created','updated','approved','retired')),
    actor TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    snapshot_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS lab_specimen_guide_history_timeline
    ON lab_specimen_guide_history(guide_id, id);
CREATE TRIGGER IF NOT EXISTS lab_specimen_guide_history_no_update
BEFORE UPDATE ON lab_specimen_guide_history
BEGIN
    SELECT RAISE(ABORT, 'lab specimen guide history is append-only');
END;
CREATE TRIGGER IF NOT EXISTS lab_specimen_guide_history_no_delete
BEFORE DELETE ON lab_specimen_guide_history
BEGIN
    SELECT RAISE(ABORT, 'lab specimen guide history is append-only');
END;
"""


def _timestamp(value: str, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise HospitalError(f"{field} must be a valid ISO-8601 timestamp")
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError as exc:
        raise HospitalError(f"{field} must be a valid ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _text(value: Any, field: str, *, required: bool = False, max_length: int = 2000) -> str:
    if not isinstance(value, str):
        raise HospitalError(f"{field} must be text")
    value = value.strip()
    if required and not value:
        raise HospitalError(f"{field} is required")
    if len(value) > max_length:
        raise HospitalError(f"{field} must be at most {max_length} characters")
    return value


def _ensure_schema(db: HospitalDB) -> None:
    with db.connect() as conn:
        conn.executescript(_SCHEMA)


def _snapshot(row: Any) -> str:
    return json.dumps(dict(row), ensure_ascii=False, sort_keys=True)


def _history(conn: Any, *, guide_id: int, version: int, action: str,
             actor: str, at: str) -> None:
    row = conn.execute("SELECT * FROM lab_specimen_guide WHERE id=?", (guide_id,)).fetchone()
    conn.execute(
        """INSERT INTO lab_specimen_guide_history
           (guide_id,version,action,actor,occurred_at,snapshot_json)
           VALUES(?,?,?,?,?,?)""",
        (guide_id, version, action, actor, at, _snapshot(row)),
    )


def create_specimen_guide_entry(
    db: HospitalDB, *, test_code: str, test_name: str, specimen_type: str,
    container: str, actor: str, at: str, preparation: str = "",
    collection_instructions: str = "", transport_conditions: str = "",
    stability: str = "", rejection_criteria: str = "",
    expected_turnaround: str = "", source_reference: str = "",
) -> int:
    """Create a draft entry. Drafts are not presented as approved clinical guidance."""
    _ensure_schema(db)
    code = _text(test_code, "test_code", required=True, max_length=80).upper()
    if not re.fullmatch(r"[A-Z0-9][A-Z0-9._-]*", code):
        raise HospitalError("test_code may contain only letters, digits, dot, underscore and hyphen")
    fields = {
        "test_name": _text(test_name, "test_name", required=True, max_length=200),
        "specimen_type": _text(specimen_type, "specimen_type", required=True, max_length=200),
        "container": _text(container, "container", required=True, max_length=200),
        "preparation": _text(preparation, "preparation"),
        "collection_instructions": _text(collection_instructions, "collection_instructions"),
        "transport_conditions": _text(transport_conditions, "transport_conditions"),
        "stability": _text(stability, "stability"),
        "rejection_criteria": _text(rejection_criteria, "rejection_criteria"),
        "expected_turnaround": _text(expected_turnaround, "expected_turnaround", max_length=300),
        "source_reference": _text(source_reference, "source_reference", max_length=500),
    }
    actor = _text(actor, "actor", required=True, max_length=120)
    at = _timestamp(at, "at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute(
            """INSERT INTO lab_specimen_guide
               (test_code,test_name,specimen_type,container,preparation,
                collection_instructions,transport_conditions,stability,rejection_criteria,
                expected_turnaround,source_reference,created_by,created_at,updated_by,updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (code, fields["test_name"], fields["specimen_type"], fields["container"],
             fields["preparation"], fields["collection_instructions"],
             fields["transport_conditions"], fields["stability"], fields["rejection_criteria"],
             fields["expected_turnaround"], fields["source_reference"], actor, at, actor, at),
        )
        guide_id = int(cur.lastrowid)
        _history(conn, guide_id=guide_id, version=1, action="created", actor=actor, at=at)
        db._audit(conn, actor, "lab.specimen_guide.created", "lab_specimen_guide", guide_id, at,
                  {"test_code": code, "status": "draft"})
        conn.commit()
        return guide_id


def update_specimen_guide_draft(
    db: HospitalDB, *, guide_id: int, actor: str, at: str, **changes: str
) -> int:
    """Update a draft only; approved content must be retired and replaced by a reviewed entry."""
    _ensure_schema(db)
    allowed = {
        "test_name": (200, True), "specimen_type": (200, True), "container": (200, True),
        "preparation": (2000, False), "collection_instructions": (2000, False),
        "transport_conditions": (2000, False), "stability": (2000, False),
        "rejection_criteria": (2000, False), "expected_turnaround": (300, False),
        "source_reference": (500, False),
    }
    if not changes or set(changes) - set(allowed):
        raise HospitalError("provide one or more supported guide fields")
    normalized = {
        key: _text(value, key, required=allowed[key][1], max_length=allowed[key][0])
        for key, value in changes.items()
    }
    actor = _text(actor, "actor", required=True, max_length=120)
    at = _timestamp(at, "at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT status,version FROM lab_specimen_guide WHERE id=?", (guide_id,)).fetchone()
        if row is None or row["status"] != "draft":
            raise HospitalError("only an existing draft guide entry can be updated")
        version = int(row["version"]) + 1
        assignments = ",".join(f"{key}=?" for key in normalized)
        conn.execute(
            f"UPDATE lab_specimen_guide SET {assignments},version=?,updated_by=?,updated_at=? WHERE id=? AND status='draft'",
            (*normalized.values(), version, actor, at, guide_id),
        )
        _history(conn, guide_id=guide_id, version=version, action="updated", actor=actor, at=at)
        db._audit(conn, actor, "lab.specimen_guide.updated", "lab_specimen_guide", guide_id, at,
                  {"version": version, "fields": sorted(normalized)})
        conn.commit()
        return version


def approve_specimen_guide_entry(
    db: HospitalDB, *, guide_id: int, reviewer: str, at: str, source_reference: str
) -> None:
    """Approve a complete draft using a named source and a reviewer distinct from its author."""
    _ensure_schema(db)
    reviewer = _text(reviewer, "reviewer", required=True, max_length=120)
    source = _text(source_reference, "source_reference", required=True, max_length=500)
    at = _timestamp(at, "at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT * FROM lab_specimen_guide WHERE id=?", (guide_id,)).fetchone()
        if row is None or row["status"] != "draft":
            raise HospitalError("only an existing draft guide entry can be approved")
        if reviewer == row["created_by"] or reviewer == row["updated_by"]:
            raise HospitalError("guide approval requires a reviewer distinct from the author/editor")
        if not row["test_name"].strip() or not row["specimen_type"].strip() or not row["container"].strip():
            raise HospitalError("test name, specimen type and container are required")
        conn.execute(
            """UPDATE lab_specimen_guide
               SET status='approved',source_reference=?,reviewed_by=?,reviewed_at=?,
                   updated_by=?,updated_at=? WHERE id=? AND status='draft'""",
            (source, reviewer, at, reviewer, at, guide_id),
        )
        _history(conn, guide_id=guide_id, version=int(row["version"]), action="approved",
                 actor=reviewer, at=at)
        db._audit(conn, reviewer, "lab.specimen_guide.approved", "lab_specimen_guide", guide_id, at,
                  {"version": int(row["version"]), "source_reference": source})
        conn.commit()


def retire_specimen_guide_entry(
    db: HospitalDB, *, guide_id: int, actor: str, at: str, reason: str
) -> None:
    _ensure_schema(db)
    actor = _text(actor, "actor", required=True, max_length=120)
    reason = _text(reason, "reason", required=True, max_length=500)
    at = _timestamp(at, "at")
    with db.connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT status,version FROM lab_specimen_guide WHERE id=?", (guide_id,)).fetchone()
        if row is None or row["status"] != "approved":
            raise HospitalError("only an approved guide entry can be retired")
        version = int(row["version"]) + 1
        conn.execute(
            "UPDATE lab_specimen_guide SET status='retired',version=?,updated_by=?,updated_at=? WHERE id=?",
            (version, actor, at, guide_id),
        )
        _history(conn, guide_id=guide_id, version=version, action="retired", actor=actor, at=at)
        db._audit(conn, actor, "lab.specimen_guide.retired", "lab_specimen_guide", guide_id, at,
                  {"version": version, "reason": reason})
        conn.commit()


def list_specimen_guide_entries(
    db: HospitalDB, *, search: str = "", approved_only: bool = True, limit: int = 50, offset: int = 0
) -> list[dict[str, Any]]:
    """Search the guide with bounded pagination; by default only approved entries are visible."""
    _ensure_schema(db)
    if type(limit) is not int or not 1 <= limit <= 100:
        raise HospitalError("limit must be an integer between 1 and 100")
    if type(offset) is not int or offset < 0:
        raise HospitalError("offset must be a non-negative integer")
    search = _text(search, "search", max_length=200)
    where = ["status='approved'"] if approved_only else ["status<>'retired'"]
    params: list[Any] = []
    if search:
        where.append("(test_code LIKE ? OR test_name LIKE ? OR specimen_type LIKE ?)")
        pattern = f"%{search}%"
        params.extend([pattern, pattern, pattern])
    with db.connect() as conn:
        rows = conn.execute(
            f"SELECT * FROM lab_specimen_guide WHERE {' AND '.join(where)} ORDER BY test_name,id LIMIT ? OFFSET ?",
            (*params, limit, offset),
        ).fetchall()
        return [dict(row) for row in rows]


def get_specimen_guide_history(db: HospitalDB, *, guide_id: int) -> list[dict[str, Any]]:
    _ensure_schema(db)
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT id,guide_id,version,action,actor,occurred_at,snapshot_json FROM lab_specimen_guide_history WHERE guide_id=? ORDER BY id",
            (guide_id,),
        ).fetchall()
        return [dict(row) for row in rows]
