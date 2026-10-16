"""Deterministic, persistence-free chart-of-accounts validation primitives.

This module deliberately does not create ledger entries, persist account balances,
or replace the existing Financial Account lifecycle. Persistence integration must
reuse the repository's established database and authorization boundaries.
"""
from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
import re
import unicodedata
from typing import Iterable, Sequence


ACCOUNT_CLASSES = frozenset({"asset", "liability", "equity", "revenue", "expense"})
POSTING_POLICIES = frozenset({"group", "posting"})
ACCOUNT_STATES = frozenset({"active", "archived"})


@dataclass(frozen=True)
class ChartAccount:
    """One chart definition; balances and financial-account lifecycle are excluded."""

    account_id: str
    scope_id: str
    code: str
    name: str
    account_class: str
    parent_id: str | None = None
    posting_policy: str = "group"
    state: str = "active"
    english_name: str | None = None
    system_managed: bool = False


@dataclass(frozen=True)
class ChartIssue:
    """Stable machine-readable validation finding."""

    code: str
    account_id: str | None
    message: str


@dataclass(frozen=True)
class DuplicateCandidate:
    account_id: str
    code: str
    name: str
    similarity: float
    reason: str


def normalize_account_code(code: str) -> str:
    """Normalize for uniqueness comparisons while preserving stored/displayed codes."""
    if not isinstance(code, str):
        raise ValueError("Account code must be text")
    return code.strip().casefold()


def normalize_account_name(name: str) -> str:
    """Normalize Arabic/Latin names for search and duplicate suggestions, not identity."""
    if not isinstance(name, str):
        raise ValueError("Account name must be text")
    value = unicodedata.normalize("NFKC", name).casefold()
    value = value.replace("ـ", "")
    value = re.sub(r"[\u064B-\u065F\u0670]", "", value)
    value = value.translate(str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي"}))
    value = re.sub(r"[^\w\s]", " ", value, flags=re.UNICODE)
    return " ".join(value.split())


def validate_chart(accounts: Iterable[ChartAccount]) -> list[ChartIssue]:
    """Return deterministic structural issues without mutating data or raising on bad rows."""
    rows = list(accounts)
    issues: list[ChartIssue] = []
    by_id: dict[str, ChartAccount] = {}
    seen_codes: dict[tuple[str, str], str] = {}

    for account in rows:
        if not isinstance(account.account_id, str) or not account.account_id.strip():
            issues.append(ChartIssue("account_id_required", None, "Account ID is required"))
            continue
        if account.account_id in by_id:
            issues.append(ChartIssue(
                "duplicate_account_id", account.account_id,
                "Account ID is duplicated in the supplied chart",
            ))
            continue
        by_id[account.account_id] = account

        valid_scope = isinstance(account.scope_id, str) and bool(account.scope_id.strip())
        if not valid_scope:
            issues.append(ChartIssue("scope_required", account.account_id, "Chart scope is required"))
        if not isinstance(account.code, str) or not account.code.strip():
            issues.append(ChartIssue("code_required", account.account_id, "Account code is required"))
        elif valid_scope:
            key = (account.scope_id, normalize_account_code(account.code))
            if key in seen_codes:
                issues.append(ChartIssue(
                    "duplicate_code", account.account_id,
                    f"Account code duplicates account {seen_codes[key]} within this scope",
                ))
            else:
                seen_codes[key] = account.account_id
        if not isinstance(account.name, str) or not account.name.strip():
            issues.append(ChartIssue("name_required", account.account_id, "Account name is required"))
        if not isinstance(account.account_class, str) or account.account_class not in ACCOUNT_CLASSES:
            issues.append(ChartIssue(
                "invalid_account_class", account.account_id,
                f"Unsupported account class: {account.account_class!r}",
            ))
        if not isinstance(account.posting_policy, str) or account.posting_policy not in POSTING_POLICIES:
            issues.append(ChartIssue(
                "invalid_posting_policy", account.account_id,
                f"Unsupported posting policy: {account.posting_policy!r}",
            ))
        if not isinstance(account.state, str) or account.state not in ACCOUNT_STATES:
            issues.append(ChartIssue(
                "invalid_account_state", account.account_id,
                f"Unsupported account state: {account.state!r}",
            ))

    # Validate references only after indexing all IDs, so input order does not matter.
    for account in by_id.values():
        if account.parent_id is None:
            continue
        if not isinstance(account.parent_id, str) or not account.parent_id.strip():
            issues.append(ChartIssue(
                "invalid_parent_reference", account.account_id,
                "Parent account reference must be a non-empty account ID string",
            ))
            continue
        parent = by_id.get(account.parent_id)
        if parent is None:
            issues.append(ChartIssue(
                "parent_not_found", account.account_id,
                f"Parent account {account.parent_id!r} does not exist in this chart",
            ))
            continue
        if parent.account_id == account.account_id:
            issues.append(ChartIssue(
                "self_parent", account.account_id, "An account cannot be its own parent",
            ))
        if parent.scope_id != account.scope_id:
            issues.append(ChartIssue(
                "parent_scope_mismatch", account.account_id,
                "Parent and child must belong to the same chart scope",
            ))
        if parent.posting_policy != "group":
            issues.append(ChartIssue(
                "parent_must_be_group", account.account_id,
                "A posting account cannot contain child accounts",
            ))
        if (account.account_class in ACCOUNT_CLASSES
                and parent.account_class in ACCOUNT_CLASSES
                and parent.account_class != account.account_class):
            issues.append(ChartIssue(
                "parent_class_mismatch", account.account_id,
                "Parent and child account classes must match",
            ))
        if parent.state != "active" and account.state == "active":
            issues.append(ChartIssue(
                "active_child_under_archived_parent", account.account_id,
                "An active account cannot have an archived/inactive parent",
            ))

    # Three-color DFS catches cycles, including cycles longer than two nodes.
    colors: dict[str, int] = {}
    cycle_accounts: set[str] = set()

    def visit(account_id: str, trail: list[str]) -> None:
        color = colors.get(account_id, 0)
        if color == 2:
            return
        if color == 1:
            try:
                cycle_start = trail.index(account_id)
                cycle_accounts.update(trail[cycle_start:])
            except ValueError:
                cycle_accounts.add(account_id)
            return
        colors[account_id] = 1
        parent_id = by_id[account_id].parent_id
        if isinstance(parent_id, str) and parent_id in by_id:
            visit(parent_id, [*trail, account_id])
        colors[account_id] = 2

    for account_id in by_id:
        visit(account_id, [])
    for account_id in sorted(cycle_accounts):
        issues.append(ChartIssue(
            "parent_cycle", account_id, "Account hierarchy contains a parent cycle",
        ))

    return issues


def suggest_next_account_code(
    existing_codes: Iterable[str], *, prefix: str, suffix_width: int = 2
) -> str:
    """Suggest an unused code; callers must still enforce uniqueness transactionally."""
    if not isinstance(prefix, str) or not prefix.strip():
        raise ValueError("Account code prefix is required")
    if not isinstance(suffix_width, int) or isinstance(suffix_width, bool) or suffix_width < 1:
        raise ValueError("suffix_width must be a positive integer")
    normalized_prefix = prefix.strip()
    occupied: set[int] = set()
    pattern = re.compile(rf"^{re.escape(normalized_prefix)}(\d{{{suffix_width}}})$")
    for code in existing_codes:
        if not isinstance(code, str):
            continue
        match = pattern.fullmatch(code.strip())
        if match:
            occupied.add(int(match.group(1)))
    candidate = 1
    while candidate in occupied:
        candidate += 1
    if candidate >= 10**suffix_width:
        raise ValueError("No account codes remain for this prefix and suffix width")
    return f"{normalized_prefix}{candidate:0{suffix_width}d}"


def find_duplicate_candidates(
    name: str,
    accounts: Sequence[ChartAccount],
    *,
    scope_id: str,
    threshold: float = 0.78,
    limit: int = 10,
) -> list[DuplicateCandidate]:
    """Rank likely name duplicates; suggestions never merge or modify accounts."""
    normalized = normalize_account_name(name)
    if not normalized:
        return []
    if not isinstance(threshold, (int, float)) or not 0 <= threshold <= 1:
        raise ValueError("threshold must be between 0 and 1")
    if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
        raise ValueError("limit must be a positive integer")

    candidates: list[DuplicateCandidate] = []
    for account in accounts:
        # Search is a read-only helper over caller-supplied rows; malformed legacy
        # records should not make otherwise valid duplicate suggestions fail.
        if not isinstance(account.scope_id, str) or account.scope_id != scope_id:
            continue
        if not all(isinstance(value, str) and value.strip() for value in (
            account.account_id, account.code, account.name
        )):
            continue
        variants = [account.name]
        if isinstance(account.english_name, str) and account.english_name.strip():
            variants.append(account.english_name)
        normalized_variants = [
            normalize_account_name(candidate) for candidate in variants
        ]
        normalized_variants = [candidate for candidate in normalized_variants if candidate]
        scores = [
            SequenceMatcher(None, normalized, candidate).ratio()
            for candidate in normalized_variants
        ]
        score = max(scores, default=0.0)
        exact = normalized in normalized_variants
        if exact or score >= threshold:
            candidates.append(DuplicateCandidate(
                account_id=account.account_id,
                code=account.code,
                name=account.name,
                similarity=round(score, 4),
                reason="normalized_exact_match" if exact else "name_similarity",
            ))
    candidates.sort(key=lambda candidate: (
        candidate.reason != "normalized_exact_match",
        -candidate.similarity,
        candidate.code,
        candidate.account_id,
    ))
    return candidates[:limit]
