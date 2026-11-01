from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
MANUAL = ROOT / "supabase" / "manual-migrations"


class SupabaseMigrationVersionIntegrityTests(unittest.TestCase):
    def test_active_migration_versions_are_unique_and_fourteen_digits(self):
        files = sorted(MIGRATIONS.glob("*.sql"))
        versions = [path.name.split("_", 1)[0] for path in files]
        self.assertTrue(files, "active migration directory must not be empty")
        self.assertTrue(
            all(re.fullmatch(r"\d{14}", version) for version in versions),
            "every active migration must use a 14-digit timestamp version",
        )
        self.assertEqual(
            len(versions), len(set(versions)),
            "duplicate Supabase migration versions are not allowed",
        )

    def test_manually_applied_privilege_sql_is_preserved_outside_active_migrations(self):
        expected = (
            "20261023_harden_postgres_default_table_privileges.sql",
            "20261023_harden_public_table_write_grants.sql",
        )
        for name in expected:
            self.assertTrue((MANUAL / name).is_file(), f"preserve manual SQL: {name}")
            self.assertFalse((MIGRATIONS / name).exists(), f"do not auto-replay manual SQL: {name}")


if __name__ == "__main__":
    unittest.main()
