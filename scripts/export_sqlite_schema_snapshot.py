"""Export the current SQLite table/column shape as a JSON snapshot.

Read-only metadata export. This does not copy rows or change the database.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


def export_schema(database_path: str | Path) -> dict[str, object]:
    path = Path(database_path).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"SQLite database does not exist: {path}")
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as connection:
        connection.row_factory = sqlite3.Row
        tables: dict[str, dict[str, str]] = {}
        names = connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
        for row in names:
            name = str(row["name"])
            quoted = '"' + name.replace('"', '""') + '"'
            columns = connection.execute(f"PRAGMA table_info({quoted})").fetchall()
            tables[name] = {
                str(column["name"]): str(column["type"] or "ANY").upper()
                for column in columns
            }
    return {"tables": {name: {"columns": columns} for name, columns in tables.items()}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite", required=True, help="Path to the actual SQLite database file.")
    parser.add_argument("--output", required=True, help="Path to write the JSON schema snapshot.")
    args = parser.parse_args()
    snapshot = export_schema(args.sqlite)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(snapshot['tables'])} SQLite tables to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
