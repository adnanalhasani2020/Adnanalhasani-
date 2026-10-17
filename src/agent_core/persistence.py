from __future__ import annotations

import sqlite3
from pathlib import Path


MIGRATIONS_DIR = Path(__file__).resolve().parents[2] / "schema" / "migrations"


class MigrationError(RuntimeError):
    pass


def apply_migrations(connection: sqlite3.Connection) -> list[str]:
    """Apply repository migrations in lexical order, atomically per migration."""
    if connection.in_transaction:
        raise MigrationError(
            "cannot apply migrations while the connection has a caller-owned transaction"
        )

    migration_paths = sorted(MIGRATIONS_DIR.glob("*.sql"))
    prefixes: dict[str, str] = {}
    for path in migration_paths:
        prefix, separator, _name = path.stem.partition("_")
        if not separator or not prefix.isdigit():
            raise MigrationError(
                f"migration filename must start with a numeric version and underscore: {path.name}"
            )
        if prefix in prefixes:
            raise MigrationError(
                f"duplicate migration version prefix {prefix}: "
                f"{prefixes[prefix]} and {path.name}"
            )
        prefixes[prefix] = path.name

    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version TEXT PRIMARY KEY,
            applied_at TEXT NOT NULL
        )
        """
    )
    applied = {
        row[0]
        for row in connection.execute(
            "SELECT version FROM schema_migrations ORDER BY version"
        )
    }
    applied_now: list[str] = []

    for path in migration_paths:
        version = path.stem
        if version in applied:
            continue
        sql = path.read_text(encoding="utf-8")
        # sqlite3.Connection.executescript() commits any open transaction before
        # executing its script. Put the migration and its ledger entry inside
        # one explicit transaction instead of relying on the connection context
        # manager, which cannot make executescript atomic.
        quoted_version = version.replace("'", "''")
        script = (
            "BEGIN IMMEDIATE;\n"
            + sql
            + "\nINSERT OR IGNORE INTO schema_migrations(version, applied_at) "
            + f"VALUES ('{quoted_version}', strftime('%Y-%m-%dT%H:%M:%fZ','now'));\n"
            + "COMMIT;"
        )
        try:
            connection.executescript(script)
        except sqlite3.DatabaseError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise MigrationError(f"migration {version} failed: {exc}") from exc
        applied_now.append(version)
        applied.add(version)
    return applied_now


def connect_database(path: str | Path = ":memory:") -> sqlite3.Connection:
    connection = sqlite3.connect(str(path))
    connection.execute("PRAGMA foreign_keys = ON")
    apply_migrations(connection)
    return connection
