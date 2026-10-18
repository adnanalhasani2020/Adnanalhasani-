#!/usr/bin/env python3
"""Safely merge existing Hospital and Finance SQLite databases into a new file.

This tool never edits either input. It refuses to overwrite an existing output,
checks schema compatibility, preserves explicit IDs/history, and aborts on any
non-identical uniqueness conflict or broken foreign key.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from hospital.core import HospitalDB  # noqa: E402
from src.agent_core.persistence import connect_database  # noqa: E402

SKIP_TABLES = {"schema_migrations", "sqlite_sequence"}


def quote(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def user_tables(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def columns(connection: sqlite3.Connection, table: str) -> list[tuple[Any, ...]]:
    return [
        (row["name"], row["type"], row["notnull"], row["pk"])
        for row in connection.execute(f"PRAGMA table_info({quote(table)})")
    ]


def row_exists_exactly(
    destination: sqlite3.Connection, table: str, names: list[str], values: tuple[Any, ...]
) -> bool:
    predicate = " AND ".join(f"{quote(name)} IS ?" for name in names)
    return destination.execute(
        f"SELECT 1 FROM {quote(table)} WHERE {predicate} LIMIT 1", values
    ).fetchone() is not None


def copy_source(
    source_path: Path, label: str, destination: sqlite3.Connection, report: dict[str, Any]
) -> None:
    source = sqlite3.connect(f"file:{source_path}?mode=ro", uri=True)
    source.row_factory = sqlite3.Row
    try:
        integrity = source.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"{label} database integrity_check failed: {integrity}")
        source_tables = user_tables(source)
        destination_tables = user_tables(destination)
        unknown = sorted(source_tables - destination_tables - SKIP_TABLES)
        if unknown:
            raise RuntimeError(
                f"{label} has tables absent from the merged schema: {', '.join(unknown)}"
            )

        for table in sorted(source_tables - SKIP_TABLES):
            source_columns = columns(source, table)
            destination_columns = columns(destination, table)
            if source_columns != destination_columns:
                raise RuntimeError(
                    f"Schema mismatch for {label}.{table}; refusing to copy this table."
                )
            names = [item[0] for item in source_columns]
            if not names:
                continue
            placeholders = ", ".join("?" for _ in names)
            insert_sql = (
                f"INSERT INTO {quote(table)} "
                f"({', '.join(quote(name) for name in names)}) VALUES ({placeholders})"
            )
            copied = 0
            identical = 0
            for row in source.execute(f"SELECT * FROM {quote(table)}"):
                values = tuple(row[name] for name in names)
                try:
                    destination.execute(insert_sql, values)
                    copied += 1
                except sqlite3.IntegrityError as exc:
                    if row_exists_exactly(destination, table, names, values):
                        identical += 1
                        continue
                    raise RuntimeError(
                        f"Conflicting row in {label}.{table}; no data was merged. "
                        f"SQLite reported: {exc}"
                    ) from exc
            entry = report.setdefault(table, {"hospital": 0, "finance": 0, "identical_rows": 0})
            entry[label] += copied
            entry["identical_rows"] += identical
    finally:
        source.close()


def merge(hospital_path: Path, finance_path: Path, output_path: Path) -> dict[str, Any]:
    hospital_path = hospital_path.expanduser().resolve()
    finance_path = finance_path.expanduser().resolve()
    output_path = output_path.expanduser().resolve()
    if hospital_path == finance_path:
        raise ValueError("Hospital and Finance inputs must be two distinct database files.")
    if output_path in (hospital_path, finance_path):
        raise ValueError("Output must be a new file, not either source database.")
    for label, path in (("Hospital", hospital_path), ("Finance", finance_path)):
        if not path.is_file():
            raise FileNotFoundError(f"{label} database not found: {path}")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(output_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
    except FileExistsError as exc:
        raise FileExistsError(
            f"Output already exists; refusing to overwrite it: {output_path}"
        ) from exc

    destination: sqlite3.Connection | None = None
    report: dict[str, Any] = {}
    try:
        HospitalDB(output_path)
        destination = connect_database(output_path)
        destination.row_factory = sqlite3.Row
        destination.execute("PRAGMA foreign_keys = OFF")
        destination.execute("BEGIN IMMEDIATE")
        copy_source(hospital_path, "hospital", destination, report)
        copy_source(finance_path, "finance", destination, report)
        destination.commit()
        destination.execute("PRAGMA foreign_keys = ON")
        violations = destination.execute("PRAGMA foreign_key_check").fetchall()
        if violations:
            sample = [tuple(row) for row in violations[:10]]
            raise RuntimeError(
                f"Merged database has {len(violations)} foreign-key violation(s): {sample}"
            )
        integrity = destination.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"Merged database integrity_check failed: {integrity}")
        return {
            "status": "success",
            "output": str(output_path),
            "tables_with_data": len(report),
            "rows_copied": {
                "hospital": sum(item["hospital"] for item in report.values()),
                "finance": sum(item["finance"] for item in report.values()),
                "identical_duplicate_rows": sum(
                    item["identical_rows"] for item in report.values()
                ),
            },
            "tables": report,
            "integrity_check": integrity,
            "foreign_key_violations": 0,
        }
    except Exception:
        if destination is not None and destination.in_transaction:
            destination.rollback()
        if output_path.exists():
            output_path.unlink()
        raise
    finally:
        if destination is not None:
            destination.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Merge two existing SQLite databases into a new shared Hospital/Finance database."
    )
    parser.add_argument("--hospital", required=True, type=Path, help="Existing Hospital SQLite file")
    parser.add_argument("--finance", required=True, type=Path, help="Existing Finance SQLite file")
    parser.add_argument("--output", required=True, type=Path, help="New output path; must not exist")
    args = parser.parse_args()
    try:
        print(json.dumps(merge(args.hospital, args.finance, args.output), indent=2))
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
