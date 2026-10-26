"""Persist a fully settled Finance payment into the authoritative ledger.

This application is intentionally Finance-owned. It consumes already-persisted
Payment, Settlement, Obligation and Financial Account records; it does not
infer mappings from Hospital identifiers and does not support partial settlement.
"""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError, require_canonical_currency_code


@dataclass(frozen=True)
class PersistedSettlementRecognition:
    settlement_id: UUID
    financial_transaction_id: UUID
    ledger_entry_id: UUID
    financial_account_id: UUID
    amount_minor: int
    currency_code: str
    recognized_at: str
    replayed: bool = False


class PersistedSettlementRecognitionApplication:
    """Atomically recognize one fully settled obligation in Finance's database."""

    ACTION_CODE = "finance.settlement.recognize"

    @staticmethod
    @contextmanager
    def _atomic(connection):
        if connection.in_transaction:
            savepoint = "settlement_recognition_" + uuid4().hex
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
            return str(UUID(str(value)))
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

    def _authorize(self, db, *, settlement_id, context_ref,
                   authorization_grant_id, agent_action_id, now):
        context = self._uuid(context_ref, "Finance context identifier")
        grant_key = self._uuid(authorization_grant_id, "AuthorizationGrant identifier")
        action_key = self._uuid(agent_action_id, "AgentAction identifier")
        grant = db.execute(
            "SELECT agent_id,action_code,scope_ref,context_ref,state,effective_from,effective_to "
            "FROM authorization_grants WHERE authorization_grant_id=?", (grant_key,),
        ).fetchone()
        if grant is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        agent, code, scope, grant_context, state, starts, ends = grant
        if state != "active" or agent is None or code != self.ACTION_CODE:
            raise ValidationError("An active agent-bound settlement-recognition grant is required")
        if scope != settlement_id or grant_context != context:
            raise ValidationError("AuthorizationGrant scope/context does not match Settlement")
        instant = self._instant(now, "Recognition timestamp")
        if self._instant(starts, "AuthorizationGrant effective_from") > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")

        action = db.execute(
            "SELECT agent_id,action_code,target_ref,context_ref,authorization_grant_id,state "
            "FROM agent_actions WHERE agent_action_id=?", (action_key,),
        ).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        if action != (agent, self.ACTION_CODE, settlement_id, context, grant_key, "approved"):
            raise ValidationError("AgentAction does not match the approved Settlement operation")
        approval = db.execute(
            "SELECT approver_person_id FROM approvals "
            "WHERE agent_action_id=? AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1", (action_key,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Settlement recognition requires recorded human approval")
        approver = approval[0]
        if db.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,),
        ).fetchone() is None:
            raise ValidationError("Settlement recognition approver must be an active Person")
        if db.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent,),
        ).fetchone() is None:
            raise ValidationError("Settlement recognition Agent must be active")
        return approver

    @staticmethod
    def _hospital_intake_for_recognition(db, *, source_event_key, payment_id,
                                         amount_minor, currency_code):
        """Require an explicit, non-conflicted Hospital payment-to-Finance mapping."""
        if source_event_key is None:
            return None
        if not isinstance(source_event_key, str) or not source_event_key.strip():
            raise ValidationError("Hospital source_event_key must be a non-empty string")
        row = db.execute(
            "SELECT external_event_intake_id,event_type,amount_minor,currency_code,status,"
            "finance_entity_type,finance_entity_id,recognition_status,recognized_settlement_id,"
            "financial_transaction_id,ledger_entry_id "
            "FROM external_event_intake WHERE source_system='hospital' AND source_event_key=?",
            (source_event_key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Hospital source event must be durably ingested before recognition")
        (intake_id, event_type, source_amount, source_currency, status, entity_type, entity_id,
         recognition_status, recognized_settlement, recognized_transaction, recognized_entry) = row
        if event_type != "hospital.payment.received":
            raise ValidationError("Only a Hospital payment event can correlate to settlement recognition")
        if status in {"quarantined", "rejected"}:
            raise ValidationError("Hospital source event is quarantined or rejected")
        if db.execute(
            "SELECT 1 FROM external_event_intake_conflicts WHERE external_event_intake_id=? LIMIT 1",
            (intake_id,),
        ).fetchone() is not None:
            raise ValidationError("Hospital source event has payload conflicts and requires reconciliation")
        if status != "mapped" or entity_type != "payment" or entity_id != payment_id:
            raise ValidationError("Hospital source event must be explicitly mapped to this Finance Payment")
        if source_amount != amount_minor or source_currency != currency_code:
            raise ValidationError("Hospital source event amount/currency does not match the Finance Payment")
        if recognition_status == "reconciliation_required":
            raise ValidationError("Hospital recognition correlation requires manual reconciliation")
        if recognition_status == "recognized" and not all(
            (recognized_settlement, recognized_transaction, recognized_entry)
        ):
            raise ValidationError("Existing Hospital recognition correlation is incomplete")
        return {
            "intake_id": intake_id,
            "recognition_status": recognition_status,
            "settlement_id": recognized_settlement,
            "transaction_id": recognized_transaction,
            "entry_id": recognized_entry,
        }

    @staticmethod
    def _record_hospital_recognition(db, *, intake, settlement_id, transaction_id,
                                     entry_id, timestamp):
        if intake is None:
            return
        if intake["recognition_status"] == "recognized":
            if (intake["settlement_id"], intake["transaction_id"], intake["entry_id"]) != (
                settlement_id, transaction_id, entry_id
            ):
                raise ValidationError(
                    "Hospital source event is already correlated to different Finance records"
                )
            return
        changed = db.execute(
            "UPDATE external_event_intake SET recognition_status='recognized',"
            "recognized_settlement_id=?,financial_transaction_id=?,ledger_entry_id=?,"
            "last_seen_at=?,last_error=NULL WHERE external_event_intake_id=? "
            "AND recognition_status='not_started'",
            (settlement_id, transaction_id, entry_id, timestamp, intake["intake_id"]),
        ).rowcount
        if changed != 1:
            raise ValidationError("Hospital recognition correlation changed concurrently; retry safely")

    def settle_and_recognize_commerce_sale(
        self, connection, *, sale_id, payment_id, obligation_id, settlement_id,
        financial_account_id, context_ref, authorization_grant_id, agent_action_id,
        source_event_key=None, now=None, connected=True,
    ):
        """Atomically settle and recognize a fully paid, invoice-linked Commerce Sale.

        Invoice issuance and Payment completion remain separately authorized actions;
        this method verifies their persisted linkage before creating Settlement and
        Finance ledger rows. It never infers payment from Sale fulfillment.
        """
        if connected is not True:
            raise ValidationError("Finance recognition requires connectivity")
        sale_key = self._uuid(sale_id, "Sale identifier")
        payment_key = self._uuid(payment_id, "Payment identifier")
        obligation_key = self._uuid(obligation_id, "Obligation identifier")
        settlement_key = self._uuid(settlement_id, "Settlement identifier")
        account_key = self._uuid(financial_account_id, "Financial Account identifier")
        timestamp = self._timestamp(
            now if now is not None else datetime.now(timezone.utc), "Recognition timestamp"
        )

        with self._atomic(connection):
            approver = self._authorize(
                connection, settlement_id=settlement_key, context_ref=context_ref,
                authorization_grant_id=authorization_grant_id,
                agent_action_id=agent_action_id, now=timestamp,
            )
            row = connection.execute(
                "SELECT p.invoice_id,p.obligation_id,p.payer_person_id,p.payee_person_id,"
                "p.amount_minor,p.currency_code,p.state,"
                "o.creditor_person_id,o.debtor_person_id,o.amount_minor,o.currency_code,o.state,"
                "i.sale_id,i.obligation_id,i.state,"
                "s.state,s.lifecycle_state,"
                "a.person_id,a.state "
                "FROM payments p "
                "JOIN obligations o ON o.obligation_id=? "
                "JOIN invoices i ON i.invoice_id=p.invoice_id "
                "JOIN sales s ON s.sale_id=i.sale_id "
                "JOIN financial_accounts a ON a.financial_account_id=? "
                "WHERE p.payment_id=? AND p.obligation_id=o.obligation_id",
                (obligation_key, account_key, payment_key),
            ).fetchone()
            if row is None:
                raise ValidationError(
                    "Commerce Sale, issued Invoice, Payment, Obligation, or Financial Account linkage is missing"
                )
            (payment_invoice, payment_obligation, payer, payee, payment_amount, payment_currency,
             payment_state, creditor, debtor, obligation_amount, obligation_currency, obligation_state,
             invoice_sale, invoice_obligation, invoice_state, sale_state, sale_lifecycle_state,
             account_person, account_state) = row

            if invoice_sale != sale_key:
                raise ValidationError("Payment Invoice does not belong to the requested Commerce Sale")
            if invoice_obligation != obligation_key or payment_invoice is None or payment_obligation != obligation_key:
                raise ValidationError("Invoice, Payment, and Obligation must reference the same persisted chain")
            if invoice_state != "issued":
                raise ValidationError("Commerce Sale settlement requires an issued Invoice")
            if sale_state not in {"completed", "fulfilled"} or sale_lifecycle_state not in {None, "completed", "fulfilled"}:
                raise ValidationError("Only a fulfilled Commerce Sale can be settled and recognized")
            if payment_state != "completed":
                raise ValidationError("Payment must be completed before settlement")
            if payer != debtor or payee != creditor:
                raise ValidationError("Payment parties do not match the Obligation parties")
            if account_person != creditor or account_state != "active":
                raise ValidationError("Financial Account must be active and belong to the Invoice issuer/creditor")
            if type(payment_amount) is not int or type(obligation_amount) is not int:
                raise ValidationError("Finance amounts must be integer minor units")
            if payment_amount != obligation_amount:
                raise ValidationError("Partial or excess settlement is not supported")
            payment_currency = require_canonical_currency_code(
                payment_currency, label="Payment currency_code"
            )
            obligation_currency = require_canonical_currency_code(
                obligation_currency, label="Obligation currency_code"
            )
            if payment_currency != obligation_currency:
                raise ValidationError("Payment and Obligation currencies do not match")
            sale_total = connection.execute(
                "SELECT COUNT(*),COUNT(DISTINCT currency_code),MIN(currency_code),SUM(line_total_minor) "
                "FROM sale_lines WHERE sale_id=?",
                (sale_key,),
            ).fetchone()
            if (sale_total is None or sale_total[0] < 1 or sale_total[1] != 1
                    or sale_total[2] != payment_currency or sale_total[3] != obligation_amount):
                raise ValidationError(
                    "Invoice-linked Obligation and Payment must equal the persisted Sale line total and currency"
                )
            if obligation_state not in {"open", "due", "overdue", "satisfied"}:
                raise ValidationError("Obligation is not eligible for settlement")

            by_obligation = connection.execute(
                "SELECT settlement_id,payment_id,obligation_id,state,settled_at "
                "FROM settlements WHERE obligation_id=?",
                (obligation_key,),
            ).fetchone()
            if by_obligation is not None:
                existing_id, existing_payment, existing_obligation, existing_state, existing_settled_at = by_obligation
                if (existing_id, existing_payment, existing_obligation) != (
                    settlement_key, payment_key, obligation_key
                ):
                    raise ValidationError("Obligation is already linked to a different Settlement")
                if existing_state != "settled" or not existing_settled_at:
                    raise ValidationError("Existing Settlement is not final; explicit reconciliation is required")
            else:
                if obligation_state == "satisfied":
                    raise ValidationError("Satisfied Obligation has no matching Settlement; reconciliation is required")
                connection.execute(
                    "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,settled_at,"
                    "created_at,updated_at,provenance_id,version_no) "
                    "VALUES(?,?,?,'settled',?,?,?,NULL,1)",
                    (settlement_key, payment_key, obligation_key, timestamp, timestamp, timestamp),
                )
                connection.execute(
                    "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                    "historical_at,actor_context_ref,prior_version_ref,current_version_ref,"
                    "change_payload_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (str(uuid4()), "finance", settlement_key, "settlement_created", timestamp,
                     approver, None, f"settlement-state:{settlement_key}:v1",
                     f"state:settled;payment:{payment_key};obligation:{obligation_key}", timestamp),
                )

            # The nested savepoint makes settlement creation and final recognition
            # one atomic unit. Any mapping, approval, currency, or ledger failure rolls
            # back the new Settlement rather than leaving a false settled record.
            return self.recognize(
                connection, settlement_id=settlement_key,
                financial_account_id=account_key, context_ref=context_ref,
                authorization_grant_id=authorization_grant_id,
                agent_action_id=agent_action_id, source_event_key=source_event_key,
                now=timestamp, connected=True,
            )

    def recognize(self, connection, *, settlement_id, financial_account_id,
                  context_ref, authorization_grant_id, agent_action_id,
                  source_event_key=None, now=None, connected=True):
        if connected is not True:
            raise ValidationError("Finance recognition requires connectivity")
        settlement_key = self._uuid(settlement_id, "Settlement identifier")
        account_key = self._uuid(financial_account_id, "Financial Account identifier")
        timestamp = self._timestamp(
            now if now is not None else datetime.now(timezone.utc), "Recognition timestamp"
        )

        with self._atomic(connection):
            approver = self._authorize(
                connection, settlement_id=settlement_key, context_ref=context_ref,
                authorization_grant_id=authorization_grant_id,
                agent_action_id=agent_action_id, now=timestamp,
            )
            row = connection.execute(
                "SELECT s.payment_id,s.obligation_id,s.state,s.settled_at,"
                "p.payer_person_id,p.payee_person_id,p.amount_minor,p.currency_code,p.state,"
                "o.creditor_person_id,o.debtor_person_id,o.amount_minor,o.currency_code,o.state,"
                "a.person_id,a.state "
                "FROM settlements s "
                "JOIN payments p ON p.payment_id=s.payment_id "
                "JOIN obligations o ON o.obligation_id=s.obligation_id "
                "JOIN financial_accounts a ON a.financial_account_id=? "
                "WHERE s.settlement_id=?",
                (account_key, settlement_key),
            ).fetchone()
            if row is None:
                raise ValidationError("Settlement, linked Finance records, or Financial Account does not exist")
            (payment_id, obligation_id, settlement_state, settled_at,
             payer, payee, payment_amount, payment_currency, payment_state,
             creditor, debtor, obligation_amount, obligation_currency, obligation_state,
             account_person, account_state) = row

            if settlement_state != "settled" or not settled_at:
                raise ValidationError("Settlement must be persisted as settled with a settlement timestamp")
            if payment_state != "completed":
                raise ValidationError("Payment must be completed before recognition")
            if obligation_state not in {"open", "due", "overdue", "satisfied"}:
                raise ValidationError("Obligation is not eligible for settlement recognition")
            if payer != debtor or payee != creditor:
                raise ValidationError("Payment parties do not match the Obligation parties")
            if account_person != creditor:
                raise ValidationError("Financial Account must belong to the Obligation creditor/payee")
            if account_state != "active":
                raise ValidationError("Financial Account must be active")
            if type(payment_amount) is not int or type(obligation_amount) is not int:
                raise ValidationError("Finance amounts must be integer minor units")
            if payment_amount != obligation_amount:
                raise ValidationError(
                    "Partial or excess settlement is not supported; remaining obligation balances are not tracked"
                )
            payment_currency = require_canonical_currency_code(
                payment_currency, label="Payment currency_code"
            )
            obligation_currency = require_canonical_currency_code(
                obligation_currency, label="Obligation currency_code"
            )
            if payment_currency != obligation_currency:
                raise ValidationError("Payment and Obligation currencies do not match")

            intake = self._hospital_intake_for_recognition(
                connection, source_event_key=source_event_key, payment_id=payment_id,
                amount_minor=payment_amount, currency_code=payment_currency,
            )

            existing = connection.execute(
                "SELECT financial_transaction_id,financial_account_id,amount_minor,currency_code,state,"
                "recognition_source_type,recognition_source_ref,recognized_at "
                "FROM financial_transactions WHERE settlement_id=?",
                (settlement_key,),
            ).fetchone()
            if existing is not None:
                transaction_id, existing_account, amount, currency, state, source_type, source_ref, recognized_at = existing
                entry = connection.execute(
                    "SELECT ledger_entry_id,financial_account_id,amount_minor,currency_code,state "
                    "FROM ledger_entries WHERE financial_transaction_id=? AND entry_sequence=1",
                    (transaction_id,),
                ).fetchone()
                if (existing_account != account_key or amount != payment_amount
                        or currency != payment_currency or state != "recognized"
                        or source_type != "settlement" or source_ref != settlement_key):
                    raise ValidationError("Settlement recognition identity conflicts with existing Finance records")
                if entry is None or entry[1:] != (account_key, payment_amount, payment_currency, "posted"):
                    raise ValidationError("Existing recognition is incomplete; reconciliation is required")
                self._record_hospital_recognition(
                    connection, intake=intake, settlement_id=settlement_key,
                    transaction_id=transaction_id, entry_id=entry[0], timestamp=timestamp,
                )
                return PersistedSettlementRecognition(
                    UUID(settlement_key), UUID(transaction_id), UUID(entry[0]), UUID(account_key),
                    payment_amount, payment_currency, recognized_at, True,
                )

            if obligation_state == "satisfied":
                raise ValidationError(
                    "Satisfied Obligation has no matching recognition; reconciliation is required"
                )

            transaction_id, entry_id = str(uuid4()), str(uuid4())
            connection.execute(
                "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,"
                "financial_account_id,amount_minor,currency_code,state,recognition_source_type,"
                "recognition_source_ref,recognized_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,'recognized','settlement',?,?,?,?)",
                (transaction_id, settlement_key, account_key, payment_amount, payment_currency,
                 settlement_key, timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
                "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,1,'posted',?,?,?)",
                (entry_id, account_key, transaction_id, payment_amount, payment_currency,
                 timestamp, timestamp, timestamp),
            )
            connection.execute(
                "UPDATE obligations SET state='satisfied',updated_at=?,version_no=version_no+1 "
                "WHERE obligation_id=? AND state IN ('open','due','overdue')",
                (timestamp, obligation_id),
            )
            if connection.execute("SELECT changes()").fetchone()[0] != 1:
                raise ValidationError("Obligation changed during recognition; no ledger result was committed")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "finance", obligation_id, "settlement_financial_recognition", timestamp,
                 approver, f"obligation-state:{obligation_id}:v{self._obligation_prior_version(connection, obligation_id)}",
                 f"obligation-state:{obligation_id}:v{self._obligation_prior_version(connection, obligation_id)+1}",
                 f"settlement:{settlement_key}:transaction:{transaction_id}:ledger:{entry_id}", timestamp),
            )
            self._record_hospital_recognition(
                connection, intake=intake, settlement_id=settlement_key,
                transaction_id=transaction_id, entry_id=entry_id, timestamp=timestamp,
            )

        return PersistedSettlementRecognition(
            UUID(settlement_key), UUID(transaction_id), UUID(entry_id), UUID(account_key),
            payment_amount, payment_currency, timestamp, False,
        )

    @staticmethod
    def _obligation_prior_version(connection, obligation_id):
        row = connection.execute(
            "SELECT version_no FROM obligations WHERE obligation_id=?", (obligation_id,)
        ).fetchone()
        if row is None:
            raise ValidationError("Obligation disappeared during recognition")
        return row[0] - 1
