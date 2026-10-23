from __future__ import annotations

import json
import sqlite3

import pytest

from agent_core.schema_reconciliation import (
    compare_schemas,
    load_supabase_snapshot,
    sqlite_schema,
)


def test_sqlite_schema_reads_tables_and_columns():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE people (id TEXT PRIMARY KEY, label TEXT NOT NULL)")
    db.execute("CREATE TABLE entries (id INTEGER, amount BIGINT)")
    schema = sqlite_schema(db)
    assert schema["people"] == {"id": "TEXT", "label": "TEXT"}
    assert schema["entries"] == {"id": "INTEGER", "amount": "BIGINT"}


def test_compare_schemas_reports_both_directions_and_types():
    report = compare_schemas(
        {"shared": {"id": "TEXT", "local_only": "INTEGER"}, "sqlite_only": {"key": "TEXT"}},
        {"shared": {"id": "UUID", "remote_only": "JSONB"}, "supabase_only": {"key": "UUID"}},
    )
    assert report["sqlite_only_tables"] == ["sqlite_only"]
    assert report["supabase_only_tables"] == ["supabase_only"]
    assert report["columns_missing_in_supabase"] == [
        {"table": "shared", "column": "local_only", "sqlite_type": "INTEGER"}
    ]
    assert report["columns_missing_in_sqlite"] == [
        {"table": "shared", "column": "remote_only", "supabase_type": "JSONB"}
    ]
    assert report["type_differences"] == [
        {"table": "shared", "column": "id", "sqlite_type": "TEXT", "supabase_type": "UUID"}
    ]
    assert report["automatic_ddl_generated"] is False


def test_snapshot_loader_validates_structure(tmp_path):
    snapshot = tmp_path / "snapshot.json"
    snapshot.write_text(json.dumps({"tables": {"items": {"columns": {"id": "uuid"}}}}))
    assert load_supabase_snapshot(snapshot) == {"items": {"id": "UUID"}}
    snapshot.write_text(json.dumps({"tables": {"items": {"columns": ["id"]}}}))
    with pytest.raises(ValueError):
        load_supabase_snapshot(snapshot)



def test_mapped_tables_compare_different_names_without_assuming_equivalence():
    report = compare_mapped_tables(
        {"sales": {"id": "TEXT", "legacy_note": "TEXT"}},
        {"commercial_sales": {"id": "UUID", "organization_id": "UUID"}},
        [{
            "sqlite_table": "sales",
            "supabase_table": "commercial_sales",
            "status": "partial_overlap",
            "reason": "Different identity models",
        }],
    )
    assert report[0]["columns_missing_in_supabase"] == [
        {"column": "legacy_note", "sqlite_type": "TEXT"}
    ]
    assert report[0]["columns_missing_in_sqlite"] == [
        {"column": "organization_id", "supabase_type": "UUID"}
    ]
    assert report[0]["type_differences"] == [
        {"column": "id", "sqlite_type": "TEXT", "supabase_type": "UUID"}
    ]
    assert report[0]["automatic_equivalence_assumed"] is False
