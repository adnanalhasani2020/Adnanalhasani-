import pytest

from agent_core.approval_enforcement import ApprovalEnforcementAuthority
from agent_core.domain_authorization import Agent, AgentAction, Approval
from agent_core.shared import ValidationError


def test_gap1_action_requires_approved_matching_approval():
    action = AgentAction(Agent("agent").id, "review")
    approval = Approval(action.id)
    with pytest.raises(ValidationError, match="valid approval"):
        ApprovalEnforcementAuthority.execute(action, approval, lambda: "executed")
    assert action.state.value == "prepared"


def test_gap1_rejected_approval_cannot_execute_and_effect_does_not_run():
    action = AgentAction(Agent("agent").id, "review")
    approval = Approval(action.id)
    approval.reject()
    calls = []
    with pytest.raises(ValidationError):
        ApprovalEnforcementAuthority.execute(action, approval, lambda: calls.append("effect"))
    assert calls == []
    assert action.state.value == "prepared"


def test_gap1_mismatched_approval_cannot_authorize_action():
    agent = Agent("agent")
    action = AgentAction(agent.id, "review")
    other = AgentAction(agent.id, "review")
    approval = Approval(other.id)
    approval.approve()
    with pytest.raises(ValidationError, match="does not match"):
        ApprovalEnforcementAuthority.execute(action, approval, lambda: "executed")
    assert action.state.value == "prepared"


def test_gap1_valid_approval_allows_only_the_bound_action_execution():
    action = AgentAction(Agent("agent").id, "review")
    approval = Approval(action.id)
    approval.approve()
    calls = []
    result = ApprovalEnforcementAuthority.execute(
        action, approval, lambda: calls.append("effect") or {"ok": True}
    )
    assert result == {"ok": True}
    assert calls == ["effect"]
    assert action.state.value == "executed"
