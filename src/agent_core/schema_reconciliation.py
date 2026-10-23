"""Compare a live SQLite schema with an exported Supabase information_schema snapshot.

This tool intentionally reports drift only. It never executes DDL or copies rows.
Snapshot format: {"tables": {"table_name": {"columns": {"column_name": "type"}}}}
"""
from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path
from typing import Any


def sqlite_schema(connection: sqlite3.Connection) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    ).fetchall()
    for (table_name,) in rows:
        columns = connection.execute(f'PRAGMA table_info("{table_name.replace(chr(34), chr(34) * 2)}")').fetchall()
        result[table_name] = {row[1]: str(row[2] or "ANY").upper() for row in columns}
    return result


def load_supabase_snapshot(path: str | Path) -> dict[str, dict[str, str]]:
    payload: Any = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("tables"), dict):
        raise ValueError("Snapshot must contain a top-level 'tables' object.")
    result: dict[str, dict[str, str]] = {}
    for table, definition in payload["tables"].items():
        if not isinstance(table, str) or not isinstance(definition, dict):
            raise ValueError("Invalid table definition in snapshot.")
        columns = definition.get("columns")
        if not isinstance(columns, dict) or not all(
            isinstance(name, str) and isinstance(kind, str)
            for name, kind in columns.items()
        ):
            raise ValueError(f"Invalid columns for table {table!r}.")
        result[table] = {name: kind.upper() for name, kind in columns.items()}
    return result


def compare_schemas(
    sqlite_tables: dict[str, dict[str, str]],
    supabase_tables: dict[str, dict[str, str]],
) -> dict[str, Any]:
    sqlite_only_tables = sorted(set(sqlite_tables) - set(supabase_tables))
    supabase_only_tables = sorted(set(supabase_tables) - set(sqlite_tables))
    shared = sorted(set(sqlite_tables) & set(supabase_tables))
    missing_in_supabase = []
    missing_in_sqlite = []
    type_differences = []
    for table in shared:
        sqlite_columns = sqlite_tables[table]
        supabase_columns = supabase_tables[table]
        for column in sorted(set(sqlite_columns) - set(supabase_columns)):
            missing_in_supabase.append({"table": table, "column": column, "sqlite_type": sqlite_columns[column]})
        for column in sorted(set(supabase_columns) - set(sqlite_columns)):
            missing_in_sqlite.append({"table": table, "column": column, "supabase_type": supabase_columns[column]})
        for column in sorted(set(sqlite_columns) & set(supabase_columns)):
            left, right = sqlite_columns[column], supabase_columns[column]
            if not _types_compatible(left, right):
                type_differences.append({
                    "table": table, "column": column,
                    "sqlite_type": left, "supabase_type": right,
                })
    return {
        "sqlite_table_count": len(sqlite_tables),
        "supabase_table_count": len(supabase_tables),
        "sqlite_only_tables": sqlite_only_tables,
        "supabase_only_tables": supabase_only_tables,
        "shared_table_count": len(shared),
        "columns_missing_in_supabase": missing_in_supabase,
        "columns_missing_in_sqlite": missing_in_sqlite,
        "type_differences": type_differences,
        "automatic_ddl_generated": False,
        "warning": "Name/type overlap does not prove semantic equivalence; review tenant scope, keys, constraints, policies, and domain invariants before migration.",
    }


def _types_compatible(sqlite_type: str, supabase_type: str) -> bool:
    left, right = sqlite_type.upper(), supabase_type.upper()
    if left == right:
        return True
    integer_types = {"INTEGER", "INT", "BIGINT", "SMALLINT", "INT2", "INT4", "INT8"}
    text_types = {"TEXT", "VARCHAR", "CHARACTER VARYING", "CHAR"}
    number_types = {"REAL", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL"}
    if left in integer_types and right in integer_types:
        return True
    if left in text_types and right in text_types:
        return True
    if left in number_types and right in number_types:
        return True
    return False



def compare_mapped_tables(
    sqlite_tables: dict[str, dict[str, str]],
    supabase_tables: dict[str, dict[str, str]],
    mappings: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Compare differently named candidate table pairs without assuming equivalence."""
    results: list[dict[str, Any]] = []
    for mapping in mappings:
        local_name = mapping.get("sqlite_table")
        remote_name = mapping.get("supabase_table")
        if not isinstance(local_name, str) or not isinstance(remote_name, str):
            raise ValueError("Each mapping needs sqlite_table and supabase_table strings.")
        local = sqlite_tables.get(local_name)
        remote = supabase_tables.get(remote_name)
        entry: dict[str, Any] = {
            "sqlite_table": local_name,
            "supabase_table": remote_name,
            "mapping_status": mapping.get("status", "unresolved"),
            "reason": mapping.get("reason", ""),
            "sqlite_table_exists": local is not None,
            "supabase_table_exists": remote is not None,
            "columns_missing_in_supabase": [],
            "columns_missing_in_sqlite": [],
            "type_differences": [],
            "automatic_equivalence_assumed": False,
        }
        if local is not None and remote is not None:
            entry["columns_missing_in_supabase"] = [
                {"column": name, "sqlite_type": local[name]}
                for name in sorted(set(local) - set(remote))
            ]
            entry["columns_missing_in_sqlite"] = [
                {"column": name, "supabase_type": remote[name]}
                for name in sorted(set(remote) - set(local))
            ]
            entry["type_differences"] = [
                {"column": name, "sqlite_type": local[name], "supabase_type": remote[name]}
                for name in sorted(set(local) & set(remote))
                if not _types_compatible(local[name], remote[name])
            ]
        results.append(entry)
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite", required=True, help="Path to the existing SQLite database file.")
    parser.add_argument("--supabase-snapshot", required=True, help="JSON exported from Supabase information_schema.")
    parser.add_argument("--mapping", help="Optional JSON mapping registry for differently named candidate tables.")
    parser.add_argument("--output", help="Write JSON report to this path instead of stdout.")
    args = parser.parse_args()
    database_path = Path(args.sqlite)
    if not database_path.is_file():
        parser.error(f"SQLite database does not exist: {database_path}")
    with sqlite3.connect(f"file:{database_path.resolve()}?mode=ro", uri=True) as connection:
        local_schema = sqlite_schema(connection)
    remote_schema = load_supabase_snapshot(args.supabase_snapshot)
    report_data = compare_schemas(local_schema, remote_schema)
    if args.mapping:
        mapping_payload = json.loads(Path(args.mapping).read_text(encoding="utf-8"))
        if not isinstance(mapping_payload, dict) or not isinstance(mapping_payload.get("mappings"), list):
            parser.error("Mapping file must contain a top-level 'mappings' array.")
        report_data["mapped_table_comparisons"] = compare_mapped_tables(
            local_schema, remote_schema, mapping_payload["mappings"]
        )
    report = json.dumps(report_data, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(report + "\n", encoding="utf-8")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
