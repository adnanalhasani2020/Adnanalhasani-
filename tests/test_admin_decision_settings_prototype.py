"""Regression checks for the manager decision settings prototype's safety boundary."""
from pathlib import Path


PROTOTYPE = (
    Path(__file__).resolve().parents[1]
    / "docs"
    / "prototypes"
    / "admin-decision-settings.html"
)


def test_manager_settings_prototype_is_arabic_rtl_and_explicitly_non_production():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert '<html lang="ar" dir="rtl">' in html
    assert "نموذج أولي فقط" in html
    assert "لا تغيّر السياسات أو صلاحيات التشغيل" in html
    assert "لا تدخل بيانات مرضى" in html


def test_manager_activity_choices_do_not_silently_activate_unapproved_policies():
    html = PROTOTYPE.read_text(encoding="utf-8")

    for activity in (
        'value="commerce"',
        'value="health"',
        'value="education"',
        'value="finance"',
        'value="mixed"',
        'value="other"',
    ):
        assert activity in html

    assert "لا يفعّل وظائف سريرية" in html
    assert "لا يجوز للاختيار" in html or "لا تغيّر السياسات" in html
    assert "معاينة غير معتمدة" in html
    assert "لم يتم اعتماد أو تفعيل أي سياسة" in html


def test_local_prototype_renders_user_supplied_text_without_html_interpolation():
    html = PROTOTYPE.read_text(encoding="utf-8")

    # User-entered organization names and notes must be assigned as text,
    # never interpreted as executable markup.
    assert "summary.textContent=" in html
    assert "summary.innerHTML" not in html


def test_local_storage_is_described_as_browser_only_not_server_persistence():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert "localStorage" in html
    assert "لا يوجد حفظ على الخادم" in html
    assert "غير متصل بالخدمات الخلفية" in html


def test_saved_select_values_are_restored_only_when_they_match_an_allowed_option():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert "function restoreSavedValue(name,value)" in html
    assert "Array.from(field.options).some(option=>option.value===value)" in html
    assert "if(field.tagName==='SELECT')" in html


def test_saved_text_values_respect_declared_length_limits():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert "field.getAttribute('maxlength')" in html
    assert "value.slice(0,limit)" in html
    assert "if(d&&typeof d==='object'&&!Array.isArray(d))" in html


def test_prototype_warns_that_browser_saved_choices_can_persist_on_shared_devices():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert "قد تبقى الاختيارات على هذا الجهاز حتى حذفها" in html
    assert "لا تستخدم النموذج على جهاز مشترك" in html
    assert "لا تدخل بيانات مرضى أو كلمات مرور أو معلومات حساسة" in html


def test_form_controls_have_explicit_labels_and_accessible_status_announcements():
    html = PROTOTYPE.read_text(encoding="utf-8")

    # Every form control should be associated with a visible label by ID.
    import re

    controls = re.findall(r'<(?:input|select|textarea)\b[^>]*\bid="([^"]+)"', html)
    labels = re.findall(r'<label\b[^>]*\bfor="([^"]+)"', html)
    assert controls
    assert set(controls).issubset(set(labels))

    assert 'id="status" role="status" aria-live="polite"' in html
    assert ":focus-visible" in html
    assert "prefers-reduced-motion:reduce" in html


def test_preview_and_error_messages_are_announced_without_html_interpolation():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert "function announceError(message)" in html
    assert "function announceStatus(message)" in html
    assert "statusBox.setAttribute('role','alert')" in html
    assert "statusBox.setAttribute('role','status')" in html
    assert "summary.textContent=" in html
    assert "summary.innerHTML" not in html


def test_local_storage_failures_are_caught_and_reported_without_crashing_the_prototype():
    html = PROTOTYPE.read_text(encoding="utf-8")

    # Browser privacy settings, quota limits, or disabled storage can throw.
    save_handler = html.split("document.getElementById('save').addEventListener", 1)[1]
    save_handler = save_handler.split("document.getElementById('reset').addEventListener", 1)[0]
    assert "try{" in save_handler
    assert "localStorage.setItem('hospital-admin-decision-prototype',JSON.stringify(safeSettings))" in save_handler
    assert "catch(error)" in save_handler
    assert "تعذر الحفظ المحلي" in html
    assert "try{localStorage.removeItem('hospital-admin-decision-prototype')" in html
    assert "تعذر حذف النسخة المحلية المحفوظة" in html
    assert "try{const saved=localStorage.getItem('hospital-admin-decision-prototype')" in html
    assert "تعذر استعادة الاختيارات المحلية" in html


def test_reset_keeps_the_user_informed_when_local_data_cannot_be_removed():
    html = PROTOTYPE.read_text(encoding="utf-8")

    reset_handler = html.split("document.getElementById('reset').addEventListener", 1)[1]
    reset_handler = reset_handler.split("function restoreSavedValue", 1)[0]

    assert "form.reset()" in reset_handler
    assert "summary.hidden=true" in reset_handler
    assert "catch(error)" in reset_handler
    assert "document.getElementById('activity').focus()" in reset_handler

def test_reset_clears_stale_preview_and_status_before_storage_cleanup():
    html = PROTOTYPE.read_text(encoding="utf-8")

    reset_handler = html.split("document.getElementById('reset').addEventListener", 1)[1]
    reset_handler = reset_handler.split("function restoreSavedValue", 1)[0]

    # A failed localStorage removal must not leave an old decision preview visible.
    assert "summary.hidden=true" in reset_handler
    assert "summary.textContent=''" in reset_handler
    assert "statusBox.style.display='none'" in reset_handler
    assert "catch(error)" in reset_handler
    assert "تعذر حذف النسخة المحلية المحفوظة" in reset_handler

def test_preview_includes_activity_specific_guidance_without_enabling_policies():
    html = PROTOTYPE.read_text(encoding="utf-8")

    for activity in ("commerce", "health", "education", "finance", "mixed", "other"):
        assert f"{activity}:" in html

    assert "const activityRecommendations={" in html
    assert "activityRecommendations[d.activity]" in html
    assert "الأولوية المقترحة (إرشادية فقط)" in html
    assert "لا تفعّل سياسات جديدة قبل اعتمادها" in html
    assert "لم يتم اعتماد أو تفعيل أي سياسة" in html

def test_activity_selection_updates_live_advisory_guidance_before_preview():
    html = PROTOTYPE.read_text(encoding="utf-8")

    assert 'id="activity-guidance" class="summary" role="status" aria-live="polite" hidden' in html
    assert "function updateActivityGuidance()" in html
    assert "activityField.addEventListener('change',updateActivityGuidance)" in html
    assert "توصية إرشادية غير ملزمة:" in html
    assert "لا يفعّل الاختيار أي سياسة." in html


def test_reset_clears_live_activity_guidance():
    html = PROTOTYPE.read_text(encoding="utf-8")
    reset_handler = html.split("document.getElementById('reset').addEventListener", 1)[1]
    reset_handler = reset_handler.split("function restoreSavedValue", 1)[0]

    assert "guidance.hidden=true" in reset_handler
    assert "guidance.textContent=''" in reset_handler
