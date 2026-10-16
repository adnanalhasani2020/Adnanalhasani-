"""Persist opening balances as recognized Finance transactions and posted ledger entries."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError, require_canonical_currency_code


@dataclass(frozen=True)
class OpeningBalanceRecord:
    financial_transaction_id: UUID
    ledger_entry_id: UUID
    financial_account_id: UUID
    amount_minor: int
    currency_code: str
    recognition_source_ref: str
    recognized_at: str


class FinancialOpeningBalanceApplication:
    """Record one audited opening entry per Financial Account and currency."""

    ACTION_CODE = "financial_account.opening_balance"

    @staticmethod
    @contextmanager
    def _atomic(connection):
        if connection.in_transaction:
            savepoint = "opening_balance_" + uuid4().hex
            connection.execute(f"SAVEPOINT {savepoint}")
            try:
                yield
            except BaseException:
                connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
                connection.execute(f"RELEASE SAVEPOINT {savepoint}")
                raise
            else:
                connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            return
        connection.execute("BEGIN IMMEDIATE")
        try:
            yield
        except BaseException:
            connection.rollback()
            raise
        else:
            connection.commit()

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
            raise ValidationError(f"{label} must be a valid timezone-aware timestamp")
        return value.astimezone(timezone.utc)

    @classmethod
    def _timestamp(cls, value, label):
        return cls._instant(value, label).isoformat().replace("+00:00", "Z")

    def _authorize(self, connection, *, account_id, context_ref, authorization_grant_id,
                   agent_action_id, now):
        context = str(self._uuid(context_ref, "Context identifier"))
        grant_key = str(self._uuid(authorization_grant_id, "AuthorizationGrant identifier"))
        action_key = str(self._uuid(agent_action_id, "AgentAction identifier"))
        grant = connection.execute(
            "SELECT agent_id,action_code,scope_ref,context_ref,state,effective_from,effective_to "
            "FROM authorization_grants WHERE authorization_grant_id=?", (grant_key,),
        ).fetchone()
        if grant is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        agent_id, granted_action, scope_ref, grant_context, state, starts, ends = grant
        if state != "active" or agent_id is None:
            raise ValidationError("An active agent-bound AuthorizationGrant is required")
        if granted_action != self.ACTION_CODE:
            raise ValidationError(f"AuthorizationGrant does not permit action {self.ACTION_CODE}")
        if scope_ref != account_id or grant_context != context:
            raise ValidationError("AuthorizationGrant scope/context does not match opening balance")
        instant = self._instant(now, "Opening balance operation timestamp")
        starts_at = self._instant(starts, "AuthorizationGrant effective_from")
        if starts_at > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")

        action = connection.execute(
            "SELECT agent_id,action_code,target_ref,context_ref,authorization_grant_id,state "
            "FROM agent_actions WHERE agent_action_id=?", (action_key,),
        ).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        action_agent, action_code, target, action_context, bound_grant, action_state = action
        if (action_agent != agent_id or action_code != self.ACTION_CODE or target != account_id
                or action_context != context or bound_grant != grant_key):
            raise ValidationError("AgentAction does not match opening balance and grant")
        if action_state != "approved":
            raise ValidationError("Opening balance requires an approved AgentAction")
        approval = connection.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1", (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Opening balance requires recorded human approval")
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approval[0],),
        ).fetchone() is None:
            raise ValidationError("Opening balance approver must be an active persisted Person")
        if connection.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,),
        ).fetchone() is None:
            raise ValidationError("Opening balance Agent must be active")
        return approval[0]

    def record_opening_balance(
        self, connection, *, financial_account_id, amount_minor, currency_code,
        source_ref, context_ref, authorization_grant_id, agent_action_id,
        now=None, connected=True,
    ) -> OpeningBalanceRecord:
        if connected is not True:
            raise ValidationError("Opening balance recognition requires connectivity")
        account_id = str(self._uuid(financial_account_id, "Financial Account identifier"))
        if type(amount_minor) is not int:
            raise ValidationError("Opening balance amount_minor must be an integer in minor units")
        if amount_minor == 0:
            raise ValidationError("A zero opening balance needs no ledger transaction")
        currency_code = require_canonical_currency_code(
            currency_code, label="Opening balance currency_code"
        )
        if not isinstance(source_ref, str) or not source_ref.strip():
            raise ValidationError("Opening balance source_ref is required")
        source_ref = source_ref.strip()
        if len(source_ref) > 200:
            raise ValidationError("Opening balance source_ref must not exceed 200 characters")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Opening balance timestamp")
        # Stable source identity is unique per account/currency; the external
        # evidence reference is retained in domain history and may be shared by a batch.
        source_key = f"opening-balance:{account_id}:{currency_code}"

        with self._atomic(connection):
            actor = self._authorize(
                connection, account_id=account_id, context_ref=context_ref,
                authorization_grant_id=authorization_grant_id,
                agent_action_id=agent_action_id, now=timestamp,
            )
            account = connection.execute(
                "SELECT state FROM financial_accounts WHERE financial_account_id=?", (account_id,),
            ).fetchone()
            if account is None:
                raise ValidationError("Financial Account does not exist")
            if account[0] != "active":
                raise ValidationError("Opening balance can only be recorded for an active Financial Account")
            if connection.execute(
                "SELECT 1 FROM financial_transactions WHERE financial_account_id=? "
                "AND currency_code=? AND recognition_source_type='opening_balance'",
                (account_id, currency_code),
            ).fetchone() is not None:
                raise ValidationError("Opening balance already exists for this Financial Account and currency")

            transaction_id, entry_id = str(uuid4()), str(uuid4())
            connection.execute(
                "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
                "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
                "created_at,updated_at) VALUES(?,NULL,?,?,?,'recognized','opening_balance',?,?,?,?)",
                (transaction_id, account_id, amount_minor, currency_code, source_key,
                 timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
                "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
                (entry_id, account_id, transaction_id, amount_minor, currency_code,
                 timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "finance", account_id, "financial_opening_balance", timestamp, actor,
                 None, f"financial-transaction:{transaction_id}:v1",
                 f"opening_balance:{currency_code}:{amount_minor}:{source_ref}", timestamp),
            )

        return OpeningBalanceRecord(
            UUID(transaction_id), UUID(entry_id), UUID(account_id), amount_minor,
            currency_code, source_key, timestamp,
        )
