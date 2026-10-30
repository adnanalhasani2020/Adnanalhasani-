from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031010000_revoke_private_trigger_helper_execute.sql"


class PrivateSecurityDefinerExecuteHardeningTests(unittest.TestCase):
    def test_internal_trigger_and_helper_functions_are_not_client_executable(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        functions = (
            "commercial_complete_return_after_restock()",
            "commercial_customer_party_account(uuid, uuid, text)",
            "commercial_gl_payment_status_trigger()",
            "commercial_gl_return_status_trigger()",
            "commercial_gl_sale_cancel_trigger()",
            "commercial_initialize_gl_chart(uuid)",
            "commercial_org_gl_init_trigger()",
            "commercial_validate_return_completion()",
        )
        for signature in functions:
            self.assertIn(f"REVOKE ALL ON FUNCTION commercial_private.{signature} FROM PUBLIC, anon, authenticated", sql)
        self.assertIn("has_function_privilege('anon', v_signature, 'EXECUTE')", sql)
        self.assertIn("has_function_privilege('authenticated', v_signature, 'EXECUTE')", sql)

    def test_migration_does_not_revoke_intended_authenticated_private_rpcs(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertNotIn("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA commercial_private", sql)
        self.assertNotIn("REVOKE ALL ON FUNCTION commercial_private.commercial_checkout_sale", sql)
        self.assertIn("commercial_customer_party_account(uuid, uuid, text)", sql)


if __name__ == "__main__":
    unittest.main()
