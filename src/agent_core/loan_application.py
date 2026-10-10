"""Persisted Loan lifecycle, independent of Obligations and financial recognition."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


_TRANSITIONS = {
    "proposed": {"active", "cancelled"},
    "active": {"closed"},
}


@dataclass(frozen=True)
class LoanRecord:
    loan_id: UUID
    lender_person_id: UUID
    borrower_person_id: UUID
    state: str
    principal_minor: int
    currency_code: str
    maturity_at: str | None
    provider_ref: str | None
    external_loan_ref: str | None
    created_at: str
    updated_at: str
    version_no: int


class LoanApplication:
    """Create, restore, and transition Loans using the existing persisted schema.

    A Loan is an agreement between lender and borrower, not an Obligation.
    This service never creates Obligations, Payments, Settlements,
    FinancialTransactions, or LedgerEntries as side effects.
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

    def _authorize(self, connection, *, loan_id, context_ref, action_code,
                   authorization_grant_id, agent_action_id, now):
        target = str(self._uuid(loan_id, "Loan identifier"))
        context = str(self._uuid(context_ref, "Loan context identifier"))
        grant_key = str(self._uuid(authorization_grant_id, "AuthorizationGrant identifier"))
        action_key = str(self._uuid(agent_action_id, "AgentAction identifier"))
        grant = connection.execute(
            "SELECT agent_id, action_code, scope_ref, context_ref, state, effective_from, effective_to "
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
            raise ValidationError("AuthorizationGrant scope/context does not match Loan operation")
        instant = self._instant(now, "Loan operation timestamp")
        if self._instant(starts, "AuthorizationGrant effective_from") > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")
        action = connection.execute(
            "SELECT agent_id, action_code, target_ref, context_ref, authorization_grant_id, state "
            "FROM agent_actions WHERE agent_action_id=?", (action_key,),
        ).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        action_agent, action_name, action_target, action_context, bound_grant, action_state = action
        if (action_agent != agent_id or action_name != action_code or action_target != target
                or action_context != context or bound_grant != grant_key):
            raise ValidationError("AgentAction does not match Loan operation and grant")
        if action_state != "approved":
            raise ValidationError("Loan operation requires an approved AgentAction")
        approval = connection.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1", (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Loan operation requires recorded human approval")
        approver = approval[0]
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,),
        ).fetchone() is None:
            raise ValidationError("Loan approver must be an active persisted Person")
        if connection.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,),
        ).fetchone() is None:
            raise ValidationError("Loan Agent must be active")
        return approver

    def _load(self, connection, loan_id):
        key = str(self._uuid(loan_id, "Loan identifier"))
        row = connection.execute(
            "SELECT loan_id,lender_person_id,borrower_person_id,state,principal_minor,currency_code,"
            "maturity_at,provider_ref,external_loan_ref,created_at,updated_at,version_no "
            "FROM loans WHERE loan_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Loan does not exist")
        (loan, lender, borrower, state, principal, currency, maturity, provider, external,
         created, updated, version) = row
        return LoanRecord(
            UUID(loan), UUID(lender), UUID(borrower), state, principal, currency, maturity,
            provider, external, created, updated, version,
        )

    def create_loan(
        self, connection, *, loan_id, lender_person_id, borrower_person_id,
        principal_minor, currency_code, context_ref, authorization_grant_id,
        agent_action_id, maturity_at=None, provider_ref=None, external_loan_ref=None,
        now=None, connected=True,
    ) -> LoanRecord:
        if connected is not True:
            raise ValidationError("Loan writes require connectivity")
        key = str(self._uuid(loan_id, "Loan identifier"))
        lender = str(self._uuid(lender_person_id, "Lender Person identifier"))
        borrower = str(self._uuid(borrower_person_id, "Borrower Person identifier"))
        if lender == borrower:
            raise ValidationError("Loan parties must differ")
        if type(principal_minor) is not int or principal_minor <= 0:
            raise ValidationError("Loan principal_minor must be a positive integer")
        if not isinstance(currency_code, str) or not currency_code.strip():
            raise ValidationError("Loan currency_code is required")
        currency_code = currency_code.strip()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Loan creation timestamp")
        maturity = self._timestamp(maturity_at, "Loan maturity_at") if maturity_at is not None else None
        with connection:
            actor = self._authorize(
                connection, loan_id=key, context_ref=context_ref, action_code="loan.create",
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            for person, label in ((lender, "Lender"), (borrower, "Borrower")):
                if connection.execute(
                    "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (person,),
                ).fetchone() is None:
                    raise ValidationError(f"{label} must be an active persisted Person")
            connection.execute(
                "INSERT INTO loans(loan_id,lender_person_id,borrower_person_id,state,principal_minor,"
                "currency_code,maturity_at,provider_ref,external_loan_ref,created_at,updated_at,version_no) "
                "VALUES(?,?,?,'proposed',?,?,?,?,?,?,?,1)",
                (key, lender, borrower, principal_minor, currency_code, maturity, provider_ref,
                 external_loan_ref, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "financial_relations", key, "loan_state_transition", timestamp, actor,
                 None, f"loan-state:{key}:v1", "state:proposed", timestamp),
            )
        return self._load(connection, key)

    def get_loan(self, connection, loan_id) -> LoanRecord:
        return self._load(connection, loan_id)

    def transition_loan(
        self, connection, loan_id, target_state, *, expected_version, context_ref,
        authorization_grant_id, agent_action_id, now=None, connected=True,
    ) -> LoanRecord:
        if connected is not True:
            raise ValidationError("Loan writes require connectivity")
        key = str(self._uuid(loan_id, "Loan identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Loan expected_version must be a positive integer")
        if target_state not in {"active", "closed", "cancelled"}:
            raise ValidationError("Unsupported persisted Loan target state")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Loan transition timestamp")
        action_code = f"loan.transition.{target_state}"
        with connection:
            actor = self._authorize(
                connection, loan_id=key, context_ref=context_ref, action_code=action_code,
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            record = self._load(connection, key)
            if record.version_no != expected_version:
                raise ValidationError("Loan version conflict; reload before retrying")
            if target_state not in _TRANSITIONS.get(record.state, set()):
                raise ValidationError(f"Loan cannot transition from {record.state} to {target_state}")
            next_version = record.version_no + 1
            cursor = connection.execute(
                "UPDATE loans SET state=?,updated_at=?,version_no=? "
                "WHERE loan_id=? AND state=? AND version_no=?",
                (target_state, timestamp, next_version, key, record.state, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Loan update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "financial_relations", key, "loan_state_transition", timestamp, actor,
                 f"loan-state:{key}:v{record.version_no}", f"loan-state:{key}:v{next_version}",
                 f"state:{target_state}", timestamp),
            )
        return self._load(connection, key)
