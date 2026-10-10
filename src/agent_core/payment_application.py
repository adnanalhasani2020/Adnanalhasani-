"""Persisted Payments lifecycle, kept separate from Settlement and Finance recognition."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


_PAYMENT_TRANSITIONS = {
    "initiated": {"pending", "completed", "failed", "cancelled"},
    "pending": {"completed", "failed", "cancelled"},
}


@dataclass(frozen=True)
class PaymentRecord:
    payment_id: UUID
    obligation_id: UUID | None
    invoice_id: UUID | None
    payer_person_id: UUID
    payee_person_id: UUID
    amount_minor: int
    currency_code: str
    state: str
    provider_ref: str | None
    external_payment_ref: str | None
    created_at: str
    updated_at: str
    version_no: int


class PaymentApplication:
    """Create initiated Payment records and persist contract-supported transitions.

    Each operation is bound to an approved persisted AgentAction and its active,
    operation-specific AuthorizationGrant. This module does not create Settlement,
    FinancialTransaction, LedgerEntry, or Balance effects.
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

    def _authorize(self, connection, *, payment_id, context_ref, action_code,
                   authorization_grant_id, agent_action_id, now):
        payment_key = str(self._uuid(payment_id, "Payment identifier"))
        context_key = str(self._uuid(context_ref, "Payment context identifier"))
        grant_key = str(self._uuid(authorization_grant_id, "AuthorizationGrant identifier"))
        action_key = str(self._uuid(agent_action_id, "AgentAction identifier"))
        grant = connection.execute(
            "SELECT agent_id, action_code, scope_ref, context_ref, state, effective_from, effective_to "
            "FROM authorization_grants WHERE authorization_grant_id=?",
            (grant_key,),
        ).fetchone()
        if grant is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        agent_id, granted_action, scope_ref, granted_context, grant_state, starts, ends = grant
        if grant_state != "active":
            raise ValidationError("AuthorizationGrant must be active")
        if agent_id is None:
            raise ValidationError("Payment operation requires an agent-bound AuthorizationGrant")
        if granted_action != action_code:
            raise ValidationError(f"AuthorizationGrant does not permit action {action_code}")
        if scope_ref != payment_key:
            raise ValidationError("AuthorizationGrant scope does not match Payment")
        if granted_context != context_key:
            raise ValidationError("AuthorizationGrant context does not match Payment context")
        instant = self._instant(now, "Payment operation timestamp")
        if self._instant(starts, "AuthorizationGrant effective_from") > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")

        action = connection.execute(
            "SELECT agent_id, action_code, target_ref, context_ref, authorization_grant_id, state "
            "FROM agent_actions WHERE agent_action_id=?",
            (action_key,),
        ).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        action_agent, action_name, target, action_context, bound_grant, action_state = action
        if (action_agent != agent_id or action_name != action_code or target != payment_key
                or action_context != context_key or bound_grant != grant_key):
            raise ValidationError("AgentAction does not match Payment operation and grant")
        if action_state != "approved":
            raise ValidationError("Payment operation requires an approved AgentAction")
        approval = connection.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1",
            (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Payment operation requires a recorded human approval")
        approver = approval[0]
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,)
        ).fetchone() is None:
            raise ValidationError("Payment approver must be an active persisted Person")
        if connection.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,)
        ).fetchone() is None:
            raise ValidationError("Payment Agent must be active")
        return approver

    def _load(self, connection, payment_id):
        key = str(self._uuid(payment_id, "Payment identifier"))
        row = connection.execute(
            "SELECT payment_id, obligation_id, invoice_id, payer_person_id, payee_person_id, "
            "amount_minor, currency_code, state, provider_ref, external_payment_ref, "
            "created_at, updated_at, version_no FROM payments WHERE payment_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Payment does not exist")
        (pid, obligation, invoice, payer, payee, amount, currency, state, provider, external,
         created, updated, version) = row
        return PaymentRecord(
            UUID(pid), UUID(obligation) if obligation else None, UUID(invoice) if invoice else None,
            UUID(payer), UUID(payee), amount, currency, state, provider, external,
            created, updated, version,
        )

    def create_payment(
        self, connection, *, payment_id, payer_person_id, payee_person_id,
        amount_minor, currency_code, context_ref, authorization_grant_id,
        agent_action_id, obligation_id=None, invoice_id=None, provider_ref=None,
        external_payment_ref=None, now=None, connected=True,
    ) -> PaymentRecord:
        if connected is not True:
            raise ValidationError("Payment operations require connectivity")
        key = str(self._uuid(payment_id, "Payment identifier"))
        payer = str(self._uuid(payer_person_id, "Payer Person identifier"))
        payee = str(self._uuid(payee_person_id, "Payee Person identifier"))
        obligation = str(self._uuid(obligation_id, "Obligation identifier")) if obligation_id is not None else None
        invoice = str(self._uuid(invoice_id, "Invoice identifier")) if invoice_id is not None else None
        if payer == payee:
            raise ValidationError("Payment parties must differ")
        if type(amount_minor) is not int or amount_minor <= 0:
            raise ValidationError("Payment amount_minor must be a positive integer")
        if not isinstance(currency_code, str) or not currency_code.strip():
            raise ValidationError("Payment currency_code is required")
        currency_code = currency_code.strip()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Payment creation timestamp")
        with connection:
            actor = self._authorize(
                connection, payment_id=key, context_ref=context_ref, action_code="payment.create",
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id,
                now=timestamp,
            )
            for person_id, label in ((payer, "Payer"), (payee, "Payee")):
                if connection.execute(
                    "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (person_id,)
                ).fetchone() is None:
                    raise ValidationError(f"{label} must be an active persisted Person")
            if obligation is not None and connection.execute(
                "SELECT 1 FROM obligations WHERE obligation_id=?", (obligation,)
            ).fetchone() is None:
                raise ValidationError("Payment references a missing Obligation")
            if invoice is not None and connection.execute(
                "SELECT 1 FROM invoices WHERE invoice_id=?", (invoice,)
            ).fetchone() is None:
                raise ValidationError("Payment references a missing Invoice")
            connection.execute(
                "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
                "amount_minor,currency_code,state,provider_ref,external_payment_ref,created_at,updated_at,"
                "version_no) VALUES(?,?,?,?,?,?,?,'initiated',?,?,?,?,1)",
                (key, obligation, invoice, payer, payee, amount_minor, currency_code,
                 provider_ref, external_payment_ref, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "payments", key, "payment_state_transition", timestamp, actor,
                 None, f"payment-state:{key}:v1", "state:initiated", timestamp),
            )
        return self._load(connection, key)

    def get_payment(self, connection, payment_id) -> PaymentRecord:
        return self._load(connection, payment_id)

    def transition_payment(
        self, connection, payment_id, target_state, *, expected_version, context_ref,
        authorization_grant_id, agent_action_id, now=None, connected=True,
    ) -> PaymentRecord:
        if connected is not True:
            raise ValidationError("Payment operations require connectivity")
        key = str(self._uuid(payment_id, "Payment identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Payment expected_version must be a positive integer")
        if target_state not in {"pending", "completed", "failed", "cancelled"}:
            raise ValidationError("Unsupported persisted Payment target state")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Payment transition timestamp")
        action_code = f"payment.transition.{target_state}"
        with connection:
            actor = self._authorize(
                connection, payment_id=key, context_ref=context_ref, action_code=action_code,
                authorization_grant_id=authorization_grant_id, agent_action_id=agent_action_id,
                now=timestamp,
            )
            record = self._load(connection, key)
            if record.version_no != expected_version:
                raise ValidationError("Payment version conflict; reload before retrying")
            if target_state not in _PAYMENT_TRANSITIONS.get(record.state, set()):
                raise ValidationError(f"Payment cannot transition from {record.state} to {target_state}")
            next_version = record.version_no + 1
            cursor = connection.execute(
                "UPDATE payments SET state=?, updated_at=?, version_no=? "
                "WHERE payment_id=? AND state=? AND version_no=?",
                (target_state, timestamp, next_version, key, record.state, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Payment update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "payments", key, "payment_state_transition", timestamp, actor,
                 f"payment-state:{key}:v{record.version_no}",
                 f"payment-state:{key}:v{next_version}", f"state:{target_state}", timestamp),
            )
        return self._load(connection, key)
