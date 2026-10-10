"""Persisted Obligation lifecycle; deliberately independent of Payment and Settlement."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


_TRANSITIONS = {
    "proposed": {"open", "cancelled"},
    "open": {"due", "overdue", "satisfied", "cancelled"},
    "due": {"overdue", "satisfied", "cancelled"},
    "overdue": {"satisfied", "cancelled"},
}


@dataclass(frozen=True)
class ObligationRecord:
    obligation_id: UUID
    creditor_person_id: UUID
    debtor_person_id: UUID
    source_invoice_id: UUID | None
    loan_id: UUID | None
    amount_minor: int
    currency_code: str
    state: str
    due_at: str | None
    created_at: str
    updated_at: str
    version_no: int


class ObligationApplication:
    """Create, restore, and transition persisted Obligations.

    Every write is scoped to an active AuthorizationGrant, a matching approved
    AgentAction, and recorded human approval. This service only owns Obligation
    and its Domain History; it never creates Payment, Settlement, or Finance
    recognition records and never infers satisfaction from a Payment.
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

    def _authorize(self, connection, *, obligation_id, context_ref, action_code,
                   authorization_grant_id, agent_action_id, now):
        target = str(self._uuid(obligation_id, "Obligation identifier"))
        context = str(self._uuid(context_ref, "Obligation context identifier"))
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
            raise ValidationError("AuthorizationGrant scope/context does not match Obligation operation")
        instant = self._instant(now, "Obligation operation timestamp")
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
            raise ValidationError("AgentAction does not match Obligation operation and grant")
        if action_state != "approved":
            raise ValidationError("Obligation operation requires an approved AgentAction")
        approval = connection.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1", (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Obligation operation requires recorded human approval")
        approver = approval[0]
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,),
        ).fetchone() is None:
            raise ValidationError("Obligation approver must be an active persisted Person")
        if connection.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,),
        ).fetchone() is None:
            raise ValidationError("Obligation Agent must be active")
        return approver

    def _load(self, connection, obligation_id):
        key = str(self._uuid(obligation_id, "Obligation identifier"))
        row = connection.execute(
            "SELECT obligation_id, creditor_person_id, debtor_person_id, source_invoice_id, loan_id, "
            "amount_minor, currency_code, state, due_at, created_at, updated_at, version_no "
            "FROM obligations WHERE obligation_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Obligation does not exist")
        (oid, creditor, debtor, invoice, loan, amount, currency, state, due_at,
         created, updated, version) = row
        return ObligationRecord(
            UUID(oid), UUID(creditor), UUID(debtor), UUID(invoice) if invoice else None,
            UUID(loan) if loan else None, amount, currency, state, due_at, created, updated, version,
        )

    def create_obligation(
        self, connection, *, obligation_id, creditor_person_id, debtor_person_id,
        amount_minor, currency_code, context_ref, authorization_grant_id, agent_action_id,
        source_invoice_id=None, loan_id=None, due_at=None, now=None, connected=True,
    ) -> ObligationRecord:
        if connected is not True:
            raise ValidationError("Obligation writes require connectivity")
        key = str(self._uuid(obligation_id, "Obligation identifier"))
        creditor = str(self._uuid(creditor_person_id, "Creditor Person identifier"))
        debtor = str(self._uuid(debtor_person_id, "Debtor Person identifier"))
        invoice = str(self._uuid(source_invoice_id, "Source Invoice identifier")) if source_invoice_id is not None else None
        loan = str(self._uuid(loan_id, "Loan identifier")) if loan_id is not None else None
        if creditor == debtor:
            raise ValidationError("Obligation parties must differ")
        if type(amount_minor) is not int or amount_minor <= 0:
            raise ValidationError("Obligation amount_minor must be a positive integer")
        if not isinstance(currency_code, str) or not currency_code.strip():
            raise ValidationError("Obligation currency_code is required")
        currency_code = currency_code.strip()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Obligation creation timestamp")
        due_timestamp = self._timestamp(due_at, "Obligation due_at") if due_at is not None else None
        with connection:
            actor = self._authorize(
                connection, obligation_id=key, context_ref=context_ref, action_code="obligation.create",
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            for person_id, label in ((creditor, "Creditor"), (debtor, "Debtor")):
                if connection.execute(
                    "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (person_id,),
                ).fetchone() is None:
                    raise ValidationError(f"{label} must be an active persisted Person")
            if invoice is not None and connection.execute(
                "SELECT 1 FROM invoices WHERE invoice_id=?", (invoice,),
            ).fetchone() is None:
                raise ValidationError("Obligation references a missing Invoice")
            if loan is not None and connection.execute(
                "SELECT 1 FROM loans WHERE loan_id=?", (loan,),
            ).fetchone() is None:
                raise ValidationError("Obligation references a missing Loan")
            connection.execute(
                "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,source_invoice_id,"
                "loan_id,amount_minor,currency_code,state,due_at,created_at,updated_at,version_no) "
                "VALUES(?,?,?,?,?,?,?,'proposed',?,?,?,1)",
                (key, creditor, debtor, invoice, loan, amount_minor, currency_code,
                 due_timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "financial_relations", key, "obligation_state_transition", timestamp,
                 actor, None, f"obligation-state:{key}:v1", "state:proposed", timestamp),
            )
        return self._load(connection, key)

    def get_obligation(self, connection, obligation_id) -> ObligationRecord:
        return self._load(connection, obligation_id)

    def transition_obligation(
        self, connection, obligation_id, target_state, *, expected_version, context_ref,
        authorization_grant_id, agent_action_id, now=None, connected=True,
    ) -> ObligationRecord:
        if connected is not True:
            raise ValidationError("Obligation writes require connectivity")
        key = str(self._uuid(obligation_id, "Obligation identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Obligation expected_version must be a positive integer")
        if target_state not in {"open", "due", "overdue", "satisfied", "cancelled"}:
            raise ValidationError("Unsupported persisted Obligation target state")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Obligation transition timestamp")
        action_code = f"obligation.transition.{target_state}"
        with connection:
            actor = self._authorize(
                connection, obligation_id=key, context_ref=context_ref, action_code=action_code,
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id, now=timestamp,
            )
            record = self._load(connection, key)
            if record.version_no != expected_version:
                raise ValidationError("Obligation version conflict; reload before retrying")
            if target_state not in _TRANSITIONS.get(record.state, set()):
                raise ValidationError(f"Obligation cannot transition from {record.state} to {target_state}")
            next_version = record.version_no + 1
            cursor = connection.execute(
                "UPDATE obligations SET state=?, updated_at=?, version_no=? "
                "WHERE obligation_id=? AND state=? AND version_no=?",
                (target_state, timestamp, next_version, key, record.state, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Obligation update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "financial_relations", key, "obligation_state_transition", timestamp,
                 actor, f"obligation-state:{key}:v{record.version_no}",
                 f"obligation-state:{key}:v{next_version}", f"state:{target_state}", timestamp),
            )
        return self._load(connection, key)
