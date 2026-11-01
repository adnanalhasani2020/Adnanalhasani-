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

    def test_internal_financial_helpers_are_not_direct_api_entry_points(self):
        migration = (
            ROOT
            / "supabase/migrations/20261101015000_revoke_internal_financial_helper_execute.sql"
        ).read_text(encoding="utf-8")
        for function in (
            "commercial_customer_party_account(uuid,uuid,text)",
            "commercial_initialize_gl_chart(uuid)",
        ):
            self.assertIn("REVOKE ALL ON FUNCTION commercial_private." + function, migration)
        self.assertIn("FROM PUBLIC, anon, authenticated", migration)
        sql = PGTAP.read_text(encoding="utf-8")
        self.assertIn("internal customer-party and GL initialization helpers", sql)


if __name__ == "__main__":
    unittest.main()
