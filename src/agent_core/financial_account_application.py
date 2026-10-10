"""Persisted Financial Account lifecycle; owns only the Finance account record."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class FinancialAccountRecord:
    financial_account_id: UUID
    person_id: UUID
    account_type: str
    state: str
    provider_ref: str | None
    external_account_ref: str | None
    created_at: str
    updated_at: str
    version_no: int


class FinancialAccountApplication:
    """Create, restore, and close Financial Accounts with authorization and history.

    This service does not recognize transactions or create ledger entries/balances.
    Closing an account is not a reversal, deletion, or mutation of financial history.
    """

    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _instant(value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc)

    @classmethod
    def _timestamp(cls, value, label):
        return cls._instant(value, label).isoformat().replace("+00:00", "Z")

    def _authorize(self, connection, *, account_id, context_ref, action_code,
                   authorization_grant_id, agent_action_id, now):
        target = str(self._uuid(account_id, "Financial Account identifier"))
        context = str(self._uuid(context_ref, "Financial Account context identifier"))
        grant_key = str(self._uuid(authorization_grant_id, "AuthorizationGrant identifier"))
        action_key = str(self._uuid(agent_action_id, "AgentAction identifier"))
        grant = connection.execute(
            "SELECT agent_id,action_code,scope_ref,context_ref,state,effective_from,effective_to "
            "FROM authorization_grants WHERE authorization_grant_id=?", (grant_key,),
        ).fetchone()
        if grant is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        agent_id, granted_action, scope_ref, granted_context, state, starts, ends = grant
        if state != "active" or agent_id is None:
            raise ValidationError("An active agent-bound AuthorizationGrant is required")
        if granted_action != action_code:
            raise ValidationError(f"AuthorizationGrant does not permit action {action_code}")
        if scope_ref != target or granted_context != context:
            raise ValidationError("AuthorizationGrant scope/context does not match Financial Account operation")
        instant = self._instant(now, "Financial Account operation timestamp")
        if self._instant(starts, "AuthorizationGrant effective_from") > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")
        action = connection.execute(
            "SELECT agent_id,action_code,target_ref,context_ref,authorization_grant_id,state "
            "FROM agent_actions WHERE agent_action_id=?", (action_key,),
        ).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        action_agent, action_name, action_target, action_context, bound_grant, action_state = action
        if (action_agent != agent_id or action_name != action_code or action_target != target
                or action_context != context or bound_grant != grant_key):
            raise ValidationError("AgentAction does not match Financial Account operation and grant")
        if action_state != "approved":
            raise ValidationError("Financial Account operation requires an approved AgentAction")
        approval = connection.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1", (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Financial Account operation requires recorded human approval")
        approver = approval[0]
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,),
        ).fetchone() is None:
            raise ValidationError("Financial Account approver must be an active persisted Person")
        if connection.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,),
        ).fetchone() is None:
            raise ValidationError("Financial Account Agent must be active")
        return approver

    def _load(self, connection, account_id):
        key = str(self._uuid(account_id, "Financial Account identifier"))
        row = connection.execute(
            "SELECT financial_account_id,person_id,account_type,state,provider_ref,external_account_ref,"
            "created_at,updated_at,version_no FROM financial_accounts WHERE financial_account_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Financial Account does not exist")
        account, person, kind, state, provider, external, created, updated, version = row
        return FinancialAccountRecord(
            UUID(account), UUID(person), kind, state, provider, external, created, updated, version
        )

    def create_account(
        self, connection, *, financial_account_id, person_id, account_type, context_ref,
        authorization_grant_id, agent_action_id, provider_ref=None, external_account_ref=None,
        now=None, connected=True,
    ) -> FinancialAccountRecord:
        if connected is not True:
            raise ValidationError("Financial Account writes require connectivity")
        key = str(self._uuid(financial_account_id, "Financial Account identifier"))
        owner = str(self._uuid(person_id, "Financial Account Person identifier"))
        if not isinstance(account_type, str) or not account_type.strip():
            raise ValidationError("Financial Account account_type is required")
        kind = account_type.strip()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Financial Account creation timestamp")
        with connection:
            actor = self._authorize(
                connection, account_id=key, context_ref=context_ref, action_code="financial_account.create",
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            if connection.execute(
                "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (owner,),
            ).fetchone() is None:
                raise ValidationError("Financial Account owner must be an active persisted Person")
            connection.execute(
                "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,provider_ref,"
                "external_account_ref,created_at,updated_at,version_no) VALUES(?,?,?,'active',?,?,?,?,1)",
                (key, owner, kind, provider_ref, external_account_ref, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "finance", key, "financial_account_lifecycle", timestamp, actor, None,
                 f"financial-account:{key}:v1", "state:active", timestamp),
            )
        return self._load(connection, key)

    def get_account(self, connection, financial_account_id) -> FinancialAccountRecord:
        return self._load(connection, financial_account_id)

    def close_account(
        self, connection, financial_account_id, *, expected_version, context_ref,
        authorization_grant_id, agent_action_id, now=None, connected=True,
    ) -> FinancialAccountRecord:
        if connected is not True:
            raise ValidationError("Financial Account writes require connectivity")
        key = str(self._uuid(financial_account_id, "Financial Account identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Financial Account expected_version must be a positive integer")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Financial Account close timestamp")
        with connection:
            actor = self._authorize(
                connection, account_id=key, context_ref=context_ref, action_code="financial_account.close",
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            current = self._load(connection, key)
            if current.version_no != expected_version:
                raise ValidationError("Financial Account version conflict; reload before retrying")
            if current.state != "active":
                raise ValidationError("Only an active Financial Account can be closed")
            next_version = current.version_no + 1
            result = connection.execute(
                "UPDATE financial_accounts SET state='closed',updated_at=?,version_no=? "
                "WHERE financial_account_id=? AND state='active' AND version_no=?",
                (timestamp, next_version, key, expected_version),
            )
            if result.rowcount != 1:
                raise ValidationError("Concurrent Financial Account update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "finance", key, "financial_account_lifecycle", timestamp, actor,
                 f"financial-account:{key}:v{current.version_no}",
                 f"financial-account:{key}:v{next_version}", "state:closed", timestamp),
            )
        return self._load(connection, key)
