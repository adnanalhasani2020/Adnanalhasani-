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

