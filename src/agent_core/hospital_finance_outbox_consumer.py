"""Fail-closed Hospital outbox consumer for already-governed Finance payments.

The consumer is deliberately opt-in: callers must supply an explicit resolver that
reads approved mappings. It never guesses Finance IDs and never creates a Payment
or Settlement. Invoice events remain retryable until an approved invoice lifecycle
is implemented. Hospital and Finance use separate transactions; retries are safe.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable, Any

from agent_core.external_event_intake import ExternalEventIntake
from agent_core.persisted_settlement_recognition_application import (
    PersistedSettlementRecognitionApplication,
)
from hospital.finance_adapter import validate_hospital_finance_event


@dataclass(frozen=True)
class FinancePaymentContext:
    """Explicit, pre-existing Finance records returned by an approved resolver."""

    payment_id: str
    settlement_id: str
    financial_account_id: str
    context_ref: str
    authorization_grant_id: str
    agent_action_id: str


@dataclass(frozen=True)
class ConsumerRunResult:
    claimed: int
    delivered: int
    failed: int
    lease_lost: int


class HospitalFinanceOutboxConsumer:
    """Process one bounded outbox batch; never enables itself or schedules itself."""

    def __init__(
        self,
        *,
        hospital_db: Any,
        finance_connection_factory: Callable[[], Any],
        resolve_payment_context: Callable[[Any, Any], FinancePaymentContext],
        intake: ExternalEventIntake | None = None,
        recognition: PersistedSettlementRecognitionApplication | None = None,
    ) -> None:
        if not callable(finance_connection_factory) or not callable(resolve_payment_context):
            raise ValueError("Finance connection factory and explicit mapping resolver are required")
        self.hospital_db = hospital_db
        self.finance_connection_factory = finance_connection_factory
        self.resolve_payment_context = resolve_payment_context
        self.intake = intake or ExternalEventIntake()
        self.recognition = recognition or PersistedSettlementRecognitionApplication()

    @staticmethod
    def _stamp(now: datetime | str | None) -> tuple[str, datetime]:
        if now is None:
            instant = datetime.now(timezone.utc)
        elif isinstance(now, str):
            try:
                instant = datetime.fromisoformat(now.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValueError("now must be an ISO-8601 timestamp") from exc
        elif isinstance(now, datetime):
            instant = now
        else:
            raise ValueError("now must be a datetime or ISO-8601 timestamp")
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValueError("now must include a timezone")
        instant = instant.astimezone(timezone.utc)
        return instant.isoformat().replace("+00:00", "Z"), instant

    def run_once(
        self, *, now: datetime | str | None = None, lease_seconds: int = 300, limit: int = 20
    ) -> ConsumerRunResult:
        stamp, instant = self._stamp(now)
        if type(lease_seconds) is not int or not 1 <= lease_seconds <= 86400:
            raise ValueError("lease_seconds must be between 1 and 86400")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValueError("limit must be between 1 and 500")
        expired = (instant - timedelta(seconds=lease_seconds)).isoformat().replace("+00:00", "Z")
        claimed = self.hospital_db.claim_finance_outbox(
            now=stamp, lease_expired_before=expired, limit=limit
        )
        delivered = failed = lease_lost = 0

        for row in claimed:
            event_key = row.get("event_key")
            attempts = row.get("attempts")
            finance_db = None
            try:
                event = validate_hospital_finance_event(row)
                # Validation and intake are not delivery acknowledgement.
                finance_db = self.finance_connection_factory()
                self.intake.ingest(finance_db, event, now=stamp)
                finance_db.commit()

                if event.event_type != "hospital.payment.received":
                    raise RuntimeError(
                        "Invoice event remains pending: approved Finance invoice lifecycle is not enabled"
                    )

                context = self.resolve_payment_context(event, finance_db)
                if not isinstance(context, FinancePaymentContext):
                    raise RuntimeError("Approved Finance payment mapping is unavailable")
                if not all((
                    context.payment_id, context.settlement_id, context.financial_account_id,
                    context.context_ref, context.authorization_grant_id, context.agent_action_id,
                )):
                    raise RuntimeError("Approved Finance payment mapping is incomplete")

                # The Finance intake boundary verifies the target exists, is a Payment,
                # and matches the source amount/currency. Conflicts fail closed.
                self.intake.map_to_finance_entity(
                    finance_db, source_event_key=event.event_key,
                    finance_entity_type="payment", finance_entity_id=context.payment_id, now=stamp,
                )
                finance_db.commit()

                self.recognition.recognize(
                    finance_db,
                    settlement_id=context.settlement_id,
                    financial_account_id=context.financial_account_id,
                    context_ref=context.context_ref,
                    authorization_grant_id=context.authorization_grant_id,
                    agent_action_id=context.agent_action_id,
                    source_event_key=event.event_key,
                    now=stamp,
                    connected=True,
                )
                finance_db.commit()

                outcome = finance_db.execute(
                    "SELECT recognition_status,recognized_settlement_id,financial_transaction_id,"
                    "ledger_entry_id FROM external_event_intake "
                    "WHERE source_system='hospital' AND source_event_key=?",
                    (event.event_key,),
                ).fetchone()
                if not outcome or outcome[0] != "recognized" or not all(outcome[1:]):
                    raise RuntimeError("Finance durable recognition receipt is incomplete")

                if self.hospital_db.mark_finance_outbox_delivered(
                    event_key=event_key, attempts=attempts, delivered_at=stamp
                ):
                    delivered += 1
                else:
                    # Finance has committed; a stale lease must not acknowledge a newer lease.
                    # A subsequent replay is safe because Finance recognition is idempotent.
                    lease_lost += 1
            except Exception as exc:
                # Keep the error short and technical; never copy the raw Hospital payload.
                try:
                    changed = self.hospital_db.mark_finance_outbox_failed(
                        event_key=event_key,
                        attempts=attempts,
                        failed_at=stamp,
                        error=f"{type(exc).__name__}: {str(exc)[:800]}",
                    )
                    if changed:
                        failed += 1
                    else:
                        lease_lost += 1
                except Exception:
                    # Do not hide the lease uncertainty by claiming delivery.
                    lease_lost += 1
            finally:
                if finance_db is not None:
                    finance_db.close()

        return ConsumerRunResult(len(claimed), delivered, failed, lease_lost)
