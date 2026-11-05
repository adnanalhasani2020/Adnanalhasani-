from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / "supabase/migrations/20261105180000_commercial_personal_notes_reminders.sql"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialPersonalNotesScreen.kt"
STORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt"


def test_personal_notes_are_user_scoped_and_accessible_only_through_rpc():
    sql = SQL.read_text(encoding="utf-8").lower()
    assert "commercial_personal_notes" in sql
    assert "user_id uuid not null references auth.users(id)" in sql
    assert "where id=p_note_id and user_id=v_uid" in sql
    assert "enable row level security" in sql
    assert "revoke all on table public.commercial_personal_notes from public, anon, authenticated" in sql
    for rpc in ("commercial_personal_notes_list_my", "commercial_personal_notes_save_my",
                "commercial_personal_notes_complete_my", "commercial_personal_notes_archive_my"):
        assert rpc in sql


def test_android_personal_notes_screen_and_navigation_are_connected():
    screen = SCREEN.read_text(encoding="utf-8")
    store = STORE.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    assert "commercial_personal_notes_list_my" in screen
    assert "commercial_personal_notes_save_my" in screen
    assert "commercial_personal_notes_complete_my" in screen
    assert "commercial_personal_notes_archive_my" in screen
    assert "CommercialPersonalNotesScreen(" in store
    assert "onPersonalNotes" in more
    assert "ملاحظاتي وتنبيهاتي" in more


def test_reminder_limit_is_disclosed_in_ui():
    screen = SCREEN.read_text(encoding="utf-8")
    assert "التنبيهات الخلفية حتى لو كان التطبيق مغلقًا غير مفعّلة" in screen
    assert "reminder_due" in SQL.read_text(encoding="utf-8")
