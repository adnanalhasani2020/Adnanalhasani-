from typing import Callable, TypeVar

from agent_core.domain_authorization import AgentAction, Approval, ApprovalState, AuthorizationGrant, AuthorizationGrantState
from agent_core.shared import ValidationError


T = TypeVar("T")


class ApprovalEnforcementAuthority:
    """Runtime boundary enforcing approval before AgentAction execution."""

    @staticmethod
    def execute(
        action: AgentAction,
        approval: Approval | None,
        effect: Callable[[], T],
        authorization_grant: AuthorizationGrant | None = None,
    ) -> T:
        if not isinstance(action, AgentAction):
            raise ValidationError("AgentAction is required")
        if authorization_grant is None:
            raise ValidationError("AgentAction execution requires authoritative AuthorizationGrant binding")
        if not isinstance(authorization_grant, AuthorizationGrant):
            raise ValidationError("AuthorizationGrant is required")
        if authorization_grant.state != AuthorizationGrantState.ACTIVE:
            raise ValidationError("AuthorizationGrant must be ACTIVE")
        if action.authorization_grant_id != authorization_grant.id:
            raise ValidationError("AuthorizationGrant does not match AgentAction")
        if authorization_grant.subject_id != action.agent_id:
            raise ValidationError("AuthorizationGrant subject does not match AgentAction")
        if authorization_grant.action != action.action:
            raise ValidationError("AuthorizationGrant action does not match AgentAction")
        if approval is None or approval.state != ApprovalState.APPROVED:
            raise ValidationError("AgentAction execution requires valid approval")
        if approval.agent_action_id != action.id:
            raise ValidationError("Approval does not match AgentAction")
        result = effect()
        action.execute(approval, authorization_grant)
        return result
