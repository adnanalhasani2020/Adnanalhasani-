"""Migration scripts and their ledger entries must commit or roll back together."""
import sqlite3

import pytest

import agent_core.persistence as persistence


def test_failed_migration_rolls_back_ddl_and_migration_ledger(tmp_path, monkeypatch):
    migrations = tmp_path / "migrations"
    migrations.mkdir()
    (migrations / "0001_broken.sql").write_text(
        "CREATE TABLE partial_migration (id INTEGER PRIMARY KEY);\n"
        "INSERT INTO table_that_does_not_exist VALUES (1);\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(persistence, "MIGRATIONS_DIR", migrations)
    connection = sqlite3.connect(":memory:")

    with pytest.raises(persistence.MigrationError, match="0001_broken"):
        persistence.apply_migrations(connection)

    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    assert "partial_migration" not in tables
    assert connection.execute(
        "SELECT version FROM schema_migrations"
    ).fetchall() == []
    connection.close()
