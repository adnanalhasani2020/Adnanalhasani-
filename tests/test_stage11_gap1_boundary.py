import pytest

from agent_core.domain_authorization import Agent, AgentAction, Approval, AuthorizationGrant
from agent_core.shared import ValidationError


def test_gap1_direct_agent_action_execution_requires_approval():
    action = AgentAction(Agent("agent").id, "review")
    with pytest.raises(ValidationError, match="valid approval"):
        action.execute()
    assert action.state.value == "prepared"


def test_gap1_direct_agent_action_execution_accepts_only_matching_approved_approval():
    agent = Agent("agent")
    action = AgentAction(agent.id, "review")
    grant = AuthorizationGrant(agent.id, "review", "review-scope")
    grant.activate()
    action.bind_authorization_grant(grant)
    approval = Approval(action.id)
    approval.approve()
    action.execute(approval, grant)
    assert action.state.value == "executed"
