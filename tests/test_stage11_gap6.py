from uuid import uuid4

import pytest

from agent_core.integrity import IdentityResolutionAuthority
from agent_core.shared import ValidationError


def test_gap6_uniqueness_rule_normalizes_identity_type_and_value():
    authority = IdentityResolutionAuthority()
    identity_id = uuid4()
    authority.register_candidate(identity_id, "National-ID", "  ABC 123 ")
    assert authority.duplicate_candidates("national-id", "abc 123") == (identity_id,)


def test_gap6_duplicate_candidates_are_detected_and_distinct_identities_remain_distinct():
    authority = IdentityResolutionAuthority()
    first, second, distinct = uuid4(), uuid4(), uuid4()
    authority.register_candidate(first, "email", "person@example.test")
    authority.register_candidate(second, "email", "person@example.test")
    authority.register_candidate(distinct, "email", "other@example.test")
    assert set(authority.duplicate_candidates("email", "person@example.test")) == {first, second}
    assert authority.duplicate_candidates("email", "other@example.test") == (distinct,)


def test_gap6_deduplication_selects_deterministic_canonical_and_preserves_old_reference():
    authority = IdentityResolutionAuthority()
    low, high = sorted((uuid4(), uuid4()), key=str)
    authority.register_candidate(high, "national-id", "N-1")
    authority.register_candidate(low, "national-id", "N-1")
    assert authority.deduplicate("national-id", "N-1") == low
    assert authority.resolve(high) == low
    assert authority.historical_reference(high) == low
    assert authority.duplicate_candidates("national-id", "N-1") == (low,)


def test_gap6_merge_requires_explicit_policy_and_rejects_ambiguous_targets():
    authority = IdentityResolutionAuthority()
    low, high, unrelated = sorted((uuid4(), uuid4(), uuid4()), key=str)
    authority.register_candidate(low, "email", "same@example.test")
    authority.register_candidate(high, "email", "same@example.test")
    authority.register_candidate(unrelated, "email", "other@example.test")
    with pytest.raises(ValidationError, match="explicit approved policy"):
        authority.merge(high, low)
    with pytest.raises(ValidationError, match="ambiguous"):
        authority.merge(unrelated, low, policy=authority.MERGE_POLICY)
    with pytest.raises(ValidationError, match="deterministic canonical"):
        authority.merge(low, high, policy=authority.MERGE_POLICY)


def test_gap6_merge_rejects_unknown_or_already_merged_identities():
    authority = IdentityResolutionAuthority()
    low, high = sorted((uuid4(), uuid4()), key=str)
    authority.register_candidate(low, "email", "same@example.test")
    authority.register_candidate(high, "email", "same@example.test")
    assert authority.merge(high, low, policy=authority.MERGE_POLICY) == low
    with pytest.raises(ValidationError, match="already merged"):
        authority.merge(high, low, policy=authority.MERGE_POLICY)
    with pytest.raises(ValidationError, match="known identities"):
        authority.merge(uuid4(), low, policy=authority.MERGE_POLICY)


def test_gap6_merge_cycle_is_rejected_if_corrupt_redirect_state_is_present():
    authority = IdentityResolutionAuthority()
    first, second = sorted((uuid4(), uuid4()), key=str)
    authority.register_candidate(first, "email", "same@example.test")
    authority.register_candidate(second, "email", "same@example.test")
    authority._redirects[first] = second
    authority._redirects[second] = first
    with pytest.raises(ValidationError, match="cycle"):
        authority.resolve(first)
