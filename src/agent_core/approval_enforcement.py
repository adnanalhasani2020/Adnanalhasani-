from typing import Callable, TypeVar

from agent_core.domain_authorization import AgentAction, Approval, ApprovalState
from agent_core.shared import ValidationError


T = TypeVar("T")


class ApprovalEnforcementAuthority:
    """Runtime boundary enforcing approval before AgentAction execution."""

    @staticmethod
    def execute(action: AgentAction, approval: Approval | None, effect: Callable[[], T]) -> T:
        if not isinstance(action, AgentAction):
            raise ValidationError("AgentAction is required")
        if approval is None or approval.state != ApprovalState.APPROVED:
            raise ValidationError("AgentAction execution requires valid approval")
        if approval.agent_action_id != action.id:
            raise ValidationError("Approval does not match AgentAction")
        result = effect()
        action.execute(approval)
        return result
