"""Tests for persistence-free chart-of-accounts validation primitives."""
import pytest

from agent_core.chart_of_accounts import (
    ChartAccount,
    find_duplicate_candidates,
    normalize_account_code,
    normalize_account_name,
    suggest_next_account_code,
    validate_chart,
)


def account(account_id, code, name, *, scope="org-1", cls="asset", parent=None,
            policy="group", state="active", english_name=None):
    return ChartAccount(
        account_id=account_id,
        scope_id=scope,
        code=code,
        name=name,
        account_class=cls,
        parent_id=parent,
        posting_policy=policy,
        state=state,
        english_name=english_name,
    )


def issue_codes(accounts):
    return {issue.code for issue in validate_chart(accounts)}


def test_valid_chart_with_group_and_posting_child_has_no_issues():
    rows = [
        account("assets", "1", "الأصول", policy="group"),
        account("cash", "1001", "الصندوق", parent="assets", policy="posting"),
    ]
    assert validate_chart(rows) == []


def test_duplicate_code_is_scoped_and_leading_zeroes_are_preserved():
    rows = [
        account("a", "001", "النقدية", scope="org-1"),
        account("b", "001", "الصندوق", scope="org-1"),
        account("c", "001", "Cash", scope="org-2"),
    ]
    assert "duplicate_code" in issue_codes(rows)
    assert normalize_account_code(" 001 ") == "001"
    assert normalize_account_code("001") != normalize_account_code("1")


@pytest.mark.parametrize(
    ("rows", "expected"),
    [
        ([account("a", "1", "A", parent="missing")], "parent_not_found"),
        ([account("a", "1", "A", parent="a")], "self_parent"),
        (
            [account("a", "1", "A", parent="b"), account("b", "2", "B", parent="a")],
            "parent_cycle",
        ),
        (
            [account("a", "1", "A", policy="posting"),
             account("b", "2", "B", parent="a")],
            "parent_must_be_group",
        ),
        (
            [account("a", "1", "A", cls="asset"),
             account("b", "2", "B", cls="expense", parent="a")],
            "parent_class_mismatch",
        ),
        (
            [account("a", "1", "A", state="archived"),
             account("b", "2", "B", parent="a", state="active")],
            "active_child_under_archived_parent",
        ),
        (
            [account("a", "1", "A", scope="org-1"),
             account("b", "2", "B", scope="org-2", parent="a")],
            "parent_scope_mismatch",
        ),
    ],
)
def test_invalid_hierarchy_is_reported_without_mutating_input(rows, expected):
    before = list(rows)
    assert expected in issue_codes(rows)
    assert rows == before


def test_three_level_cycle_reports_each_member():
    rows = [
        account("a", "1", "A", parent="c"),
        account("b", "2", "B", parent="a"),
        account("c", "3", "C", parent="b"),
    ]
    cycle_issues = [issue for issue in validate_chart(rows) if issue.code == "parent_cycle"]
    assert {issue.account_id for issue in cycle_issues} == {"a", "b", "c"}


def test_next_code_suggestion_skips_occupied_codes_and_preserves_prefix():
    assert suggest_next_account_code(["1001", "1002", "1004", "x001"], prefix="1", suffix_width=3) == "1003"
    assert suggest_next_account_code(["C-01", "C-03"], prefix="C-", suffix_width=2) == "C-02"


def test_next_code_suggestion_rejects_invalid_width_and_exhaustion():
    with pytest.raises(ValueError, match="positive integer"):
        suggest_next_account_code([], prefix="1", suffix_width=0)
    with pytest.raises(ValueError, match="No account codes remain"):
        suggest_next_account_code([f"A{i:02d}" for i in range(1, 100)], prefix="A", suffix_width=2)


def test_arabic_normalization_handles_alef_variants_diacritics_and_spacing():
    assert normalize_account_name("  إيراداتُ   المبيعات ") == normalize_account_name("ايرادات المبيعات")
    assert normalize_account_name("الصـندوق") == normalize_account_name("الصندوق")


def test_duplicate_suggestions_are_scoped_ranked_and_never_mutate_accounts():
    rows = [
        account("a", "1001", "النقدية", english_name="Cash"),
        account("b", "1002", "النقدية", scope="org-2"),
        account("c", "1003", "الصندوق"),
    ]
    before = list(rows)
    matches = find_duplicate_candidates(" النقدية ", rows, scope_id="org-1")
    assert [match.account_id for match in matches] == ["a"]
    assert matches[0].reason == "normalized_exact_match"
    assert rows == before



def test_malformed_runtime_field_types_are_reported_instead_of_crashing():
    rows = [
        ChartAccount(
            account_id="a",
            scope_id=[],
            code="001",
            name="Cash",
            account_class=[],
            parent_id=[],
            posting_policy={},
            state=[],
        )
    ]
    codes = issue_codes(rows)
    assert "scope_required" in codes
    assert "invalid_account_class" in codes
    assert "invalid_parent_reference" in codes
    assert "invalid_posting_policy" in codes
    assert "invalid_account_state" in codes


def test_duplicate_suggestion_validates_threshold_and_limit():
    with pytest.raises(ValueError, match="threshold"):
        find_duplicate_candidates("cash", [], scope_id="org-1", threshold=1.5)
    with pytest.raises(ValueError, match="limit"):
        find_duplicate_candidates("cash", [], scope_id="org-1", limit=0)

def test_duplicate_suggestions_skip_malformed_account_fields():
    rows = [
        account("valid", "1001", "النقدية"),
        ChartAccount(
            account_id="bad-name",
            scope_id="org-1",
            code="1002",
            name=None,
            english_name=[],
            account_class="asset",
        ),
        ChartAccount(
            account_id="bad-code",
            scope_id="org-1",
            code=[],
            name="النقدية",
            account_class="asset",
        ),
        ChartAccount(
            account_id="bad-scope",
            scope_id=[],
            code="1003",
            name="النقدية",
            account_class="asset",
        ),
    ]
    matches = find_duplicate_candidates("النقدية", rows, scope_id="org-1")
    assert [match.account_id for match in matches] == ["valid"]

