import pytest

from agent_core.approval_enforcement import ApprovalEnforcementAuthority
from agent_core.domain_authorization import Agent, AgentAction, Approval, AuthorizationGrant
from agent_core.shared import ValidationError


def _bound_action():
    agent = Agent("agent")
    action = AgentAction(agent.id, "review")
    grant = AuthorizationGrant(agent.id, "review", "review-scope")
    grant.activate()
    action.bind_authorization_grant(grant)
    return action, grant


def _approved(action):
    approval = Approval(action.id)
    approval.approve()
    return approval


def test_correct_grant_to_correct_action_is_allowed_with_approval():
    action, grant = _bound_action()
    approval = _approved(action)
    calls = []

    result = ApprovalEnforcementAuthority.execute(
        action, approval, lambda: calls.append("effect") or "ok", grant
    )

    assert result == "ok"
    assert calls == ["effect"]
    assert action.state.value == "executed"


def test_wrong_grant_to_action_is_rejected_without_effect():
    action, grant = _bound_action()
    other = AuthorizationGrant(action.agent_id, action.action, "other-scope")
    other.activate()
    approval = _approved(action)
    calls = []

    with pytest.raises(ValidationError, match="does not match"):
        ApprovalEnforcementAuthority.execute(
            action, approval, lambda: calls.append("effect"), other
        )

    assert calls == []
    assert action.state.value == "prepared"
    assert action.authorization_grant_id == grant.id


def test_missing_binding_is_rejected_without_effect():
    agent = Agent("agent")
    action = AgentAction(agent.id, "review")
    grant = AuthorizationGrant(agent.id, "review", "scope")
    grant.activate()
    approval = _approved(action)
    calls = []

    with pytest.raises(ValidationError, match="does not match"):
        ApprovalEnforcementAuthority.execute(
            action, approval, lambda: calls.append("effect"), grant
        )

    assert calls == []
    assert action.state.value == "prepared"


def test_duplicate_binding_is_rejected():
    action, grant = _bound_action()

    with pytest.raises(ValidationError, match="already has"):
        action.bind_authorization_grant(grant)


def test_second_authoritative_grant_for_same_action_is_rejected():
    action, first = _bound_action()
    second = AuthorizationGrant(action.agent_id, action.action, "second-scope")
    second.activate()

    with pytest.raises(ValidationError, match="already has"):
        action.bind_authorization_grant(second)

    assert action.authorization_grant_id == first.id


@pytest.mark.parametrize("transition", ["suspend", "revoke", "expire"])
def test_non_active_grant_states_reject_execution_without_effect(transition):
    action, grant = _bound_action()
    getattr(grant, transition)()
    approval = _approved(action)
    calls = []

    with pytest.raises(ValidationError, match="ACTIVE"):
        ApprovalEnforcementAuthority.execute(
            action, approval, lambda: calls.append("effect"), grant
        )

    assert calls == []
    assert action.state.value == "prepared"


def test_valid_grant_and_valid_gap1_approval_are_both_required():
    action, grant = _bound_action()
    calls = []

    with pytest.raises(ValidationError, match="valid approval"):
        ApprovalEnforcementAuthority.execute(
            action, None, lambda: calls.append("effect"), grant
        )

    assert calls == []
    assert action.state.value == "prepared"

    approval = _approved(action)
    ApprovalEnforcementAuthority.execute(
        action, approval, lambda: calls.append("effect"), grant
    )
    assert calls == ["effect"]
    assert action.state.value == "executed"


def test_rejected_approval_produces_no_execution_effect():
    action, grant = _bound_action()
    approval = Approval(action.id)
    approval.reject()
    calls = []

    with pytest.raises(ValidationError):
        ApprovalEnforcementAuthority.execute(
            action, approval, lambda: calls.append("effect"), grant
        )

    assert calls == []
    assert action.state.value == "prepared"


def test_grant_subject_or_action_mismatch_cannot_be_bound():
    agent = Agent("agent")
    other = Agent("other")
    action = AgentAction(agent.id, "review")

    wrong_subject = AuthorizationGrant(other.id, "review", "scope")
    with pytest.raises(ValidationError, match="subject"):
        action.bind_authorization_grant(wrong_subject)

    wrong_action = AuthorizationGrant(agent.id, "approve", "scope")
    with pytest.raises(ValidationError, match="action"):
        action.bind_authorization_grant(wrong_action)

    assert action.authorization_grant_id is None


def test_historical_binding_identity_is_preserved_after_grant_lifecycle_change():
    action, grant = _bound_action()
    bound_id = action.authorization_grant_id

    grant.suspend()
    grant.revoke()
    grant.expire()

    assert action.authorization_grant_id == bound_id == grant.id
