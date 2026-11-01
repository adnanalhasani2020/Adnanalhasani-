from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"


class CommercialInvoiceNotificationAndRpcContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = "\n".join(p.read_text(encoding="utf-8") for p in MIGRATIONS.glob("*.sql"))
        cls.catalog = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        cls.notifications = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialNotificationsScreen.kt").read_text(encoding="utf-8")

    def test_android_catalog_and_notification_rpc_names_have_database_definitions(self):
        sources = self.catalog + "\n" + self.notifications
        rpc_names = set(re.findall(r"/rest/v1/rpc/([a-zA-Z0-9_]+)", sources))
        defined = set(re.findall(
            r"CREATE(?: OR REPLACE)? FUNCTION\\s+public\\.([a-zA-Z0-9_]+)\\s*\\(",
            self.sql,
            flags=re.IGNORECASE,
        ))
        self.assertTrue(rpc_names, "expected Android RPC calls")
        self.assertEqual(sorted(rpc_names - defined), [], "Android RPC has no SQL definition in repository migrations")

    def test_invoice_transfer_notifications_are_transactional_and_recipient_scoped(self):
        sql = (MIGRATIONS / "20261102010000_commercial_invoice_purchase_notifications.sql").read_text(encoding="utf-8")
        for marker in (
            "AFTER INSERT OR UPDATE OF status",
            "OLD.status = 'pending'",
            "NEW.status IN ('approved', 'rejected')",
            "m.organization_id = v_org_id",
            "m.state = 'active'",
            "m.role IN ('owner', 'admin', 'manager')",
            "'invoice_purchase_request', NEW.id",
            "commercial_user_notifications",
            "REVOKE ALL ON FUNCTION public.commercial_notify_invoice_purchase_request_event()",
        ):
            self.assertIn(marker, sql)
        self.assertIn("commercial_user_notifications_list_my", self.notifications)
        self.assertIn("commercial_user_notifications_mark_read", self.notifications)

    def test_invoice_transfer_notification_is_not_sent_for_idempotent_replays(self):
        request_sql = (MIGRATIONS / "20261031020000_invoice_purchase_line_integrity.sql").read_text(encoding="utf-8")
        self.assertIn("IF FOUND THEN", request_sql)
        self.assertIn("'idempotent_replay',true", request_sql)
        self.assertIn("INSERT INTO public.commercial_invoice_purchase_requests", request_sql)
        self.assertIn("AFTER INSERT OR UPDATE OF status", (MIGRATIONS / "20261102010000_commercial_invoice_purchase_notifications.sql").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
