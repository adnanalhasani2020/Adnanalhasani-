from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PGTAP = ROOT / "supabase/tests/database/commercial_integrity_test.sql"


class CommercialSecurityDefinerAuditContractTests(unittest.TestCase):
    def test_database_gate_audits_every_public_security_definer_function(self):
        sql = PGTAP.read_text(encoding="utf-8")
        self.assertIn("p.prosecdef", sql)
        self.assertIn("n.nspname = 'public'", sql)
        self.assertIn("has_function_privilege('anon', p.oid, 'EXECUTE')", sql)
        self.assertIn("setting LIKE 'search_path=%'", sql)
        self.assertIn("all public SECURITY DEFINER functions pin search_path", sql)

    def test_empty_search_path_is_accepted_as_an_explicit_hardening_setting(self):
        sql = PGTAP.read_text(encoding="utf-8")
        self.assertIn("explicitly empty path is also pinned", sql)
        self.assertIn("coalesce(p.proconfig, ARRAY[]::text[])", sql)


if __name__ == "__main__":
    unittest.main()
