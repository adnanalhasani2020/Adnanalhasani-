from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261105180000_commercial_personal_work_notes.sql"


def test_reminder_notification_type_is_added_before_personal_note_reminders_are_used():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    constraint = "add constraint commercial_user_notifications_notification_type_check"
    assert "drop constraint if exists commercial_user_notifications_notification_type_check" in sql
    assert constraint in sql
    assert "'message','reminder'" in sql
    assert sql.index(constraint) < sql.index("'reminder','تذكير بمهمة")


def test_work_notes_reminders_are_user_scoped_and_idempotent():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "v_uid uuid := auth.uid()" in sql
    assert "where n.user_id=v_uid" in sql
    assert "reminder_notified_at" in sql
    assert "n.reminder_at<=now() and n.reminder_notified_at is null" in sql
    assert "revoke all on table public.commercial_user_work_notes from public,anon,authenticated" in sql
