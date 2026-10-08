from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from threading import RLock
from uuid import UUID

from agent_core.approval_enforcement import ApprovalEnforcementAuthority
from agent_core.domain_authorization import AgentAction, Approval, AuthorizationGrant
from agent_core.domain_finance import (
    Balance, FinanceRecognitionCommand, FinancialAccount, Obligation, ObligationState,
    Payment, PaymentState, Settlement, SettlementState,
)
from agent_core.integrity import DurableOperationAuthority
from agent_core.shared import ValidationError

class WorkflowState(str, Enum):
    CREATED="created"; IN_PROGRESS="in_progress"; RECOGNIZED="recognized"; FAILED="failed"; CANCELLED="cancelled"; REVERSED="reversed"

@dataclass(frozen=True)
class WorkflowEvent:
    workflow_id: UUID
    state: WorkflowState
    event: str
    occurred_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    detail: str | None = None
    def as_dict(self):
        return {"workflow_id":str(self.workflow_id),"state":self.state.value,"event":self.event,"occurred_at":self.occurred_at.isoformat(),"detail":self.detail}

@dataclass(frozen=True)
class RecognitionResult:
    workflow_id: UUID
    transaction_id: UUID
    ledger_entry_id: UUID
    amount: Decimal
    state: WorkflowState
    history: tuple[dict,...]

class FinancialOrchestrator:
    """Finance-scoped orchestration boundary; Finance remains Ledger authority."""
    NAMESPACE="finance.gap4.recognition"; OPERATION_KIND="recognize"; REVERSAL_NAMESPACE="finance.gap4.reversal"; CORRECTION_NAMESPACE="finance.gap4.correction"
    def __init__(self, operations: DurableOperationAuthority, recognition: FinanceRecognitionCommand|None=None):
        self.operations=operations; self.recognition=recognition or FinanceRecognitionCommand()
        self._lock=RLock(); self._workflows={}

    @staticmethod
    def _require_uuid(value,name):
        if not isinstance(value,UUID): raise ValidationError(f"{name} must be UUID")

    @staticmethod
    def _validate_matching(payment,settlement,obligation):
        if not isinstance(payment,Payment) or not isinstance(settlement,Settlement) or not isinstance(obligation,Obligation):
            raise ValidationError("Payment, Settlement, and Obligation are required")
        if settlement.payment_id!=payment.id: raise ValidationError("Settlement does not match Payment")
        if settlement.obligation_id!=obligation.id: raise ValidationError("Settlement does not match Obligation")
        if payment.obligation_id is not None and payment.obligation_id!=obligation.id: raise ValidationError("Payment does not match Obligation")
        if payment.state!=PaymentState.COMPLETED: raise ValidationError("Payment must be COMPLETED")
        if settlement.state!=SettlementState.SETTLED: raise ValidationError("Settlement must be SETTLED")
        if obligation.state not in (ObligationState.OPEN,ObligationState.DUE,ObligationState.OVERDUE):
            raise ValidationError("Obligation is not settlement-eligible")
        if payment.amount>obligation.amount: raise ValidationError("Payment exceeds Obligation")

    @staticmethod
    def _result_from_payload(payload):
        return RecognitionResult(UUID(payload["workflow_id"]),UUID(payload["transaction_id"]),UUID(payload["ledger_entry_id"]),Decimal(payload["amount"]),WorkflowState(payload["state"]),tuple(payload["history"]))

    def recognize(self, *, workflow_id, payment, settlement, obligation, account, action, approval, grant, connected):
        self._require_uuid(workflow_id,"WorkflowId")
        if not isinstance(account,FinancialAccount): raise ValidationError("FinancialAccount is required")
        if account.state.value!="active": raise ValidationError("FinancialAccount must be ACTIVE")
        request={"workflow_id":str(workflow_id),"payment_id":str(payment.id),"settlement_id":str(settlement.id),"obligation_id":str(obligation.id),"amount":str(payment.amount)}

        def recognition_effect():
            history=[WorkflowEvent(workflow_id,WorkflowState.CREATED,"workflow_created")]
            try:
                history.append(WorkflowEvent(workflow_id,WorkflowState.IN_PROGRESS,"integrity_checks"))
                self._validate_matching(payment,settlement,obligation)
                if not connected: raise ValidationError("Financial finality requires connectivity")
                transaction,ledger=self.recognition.recognize(financial_account_id=account.id,amount=payment.amount)
                obligation.satisfy()
                history.append(WorkflowEvent(workflow_id,WorkflowState.RECOGNIZED,"financial_recognition",detail=str(ledger.id)))
                result=RecognitionResult(workflow_id,transaction.id,ledger.id,payment.amount,WorkflowState.RECOGNIZED,tuple(e.as_dict() for e in history))
                with self._lock: self._workflows[workflow_id]=result
                return {"workflow_id":str(result.workflow_id),"transaction_id":str(result.transaction_id),"ledger_entry_id":str(result.ledger_entry_id),"amount":str(result.amount),"state":result.state.value,"history":list(result.history)}
            except Exception:
                with self._lock: self._workflows.pop(workflow_id,None)
                raise

        payload=ApprovalEnforcementAuthority.execute(
            action,approval,
            lambda:self.operations.execute(namespace=self.NAMESPACE,operation_id=str(workflow_id),operation_kind=self.OPERATION_KIND,request=request,effect=recognition_effect),
            grant,
        )
        return self._result_from_payload(payload)

    def cancel(self,workflow_id):
        self._require_uuid(workflow_id,"WorkflowId")
        with self._lock:
            existing=self._workflows.get(workflow_id)
            if existing and existing.state==WorkflowState.RECOGNIZED: raise ValidationError("Recognized workflow cannot be cancelled")
        return WorkflowEvent(workflow_id,WorkflowState.CANCELLED,"workflow_cancelled")

    def reverse(self, *, workflow_id, account, original, action, approval, grant, connected):
        self._require_uuid(workflow_id,"WorkflowId")
        if original.state!=WorkflowState.RECOGNIZED: raise ValidationError("Only recognized workflow can be reversed")
        def effect():
            if not connected: raise ValidationError("Financial finality requires connectivity")
            tx,entry=self.recognition.recognize(financial_account_id=account.id,amount=-original.amount)
            return {"workflow_id":str(workflow_id),"transaction_id":str(tx.id),"ledger_entry_id":str(entry.id),"amount":str(-original.amount),"state":WorkflowState.REVERSED.value,"history":list(original.history+(WorkflowEvent(workflow_id,WorkflowState.REVERSED,"workflow_reversed",detail=str(entry.id)).as_dict(),))}
        payload=ApprovalEnforcementAuthority.execute(
            action,approval,
            lambda:self.operations.execute(
                namespace=self.REVERSAL_NAMESPACE,operation_id=str(workflow_id),operation_kind="reverse",
                request={"workflow_id":str(workflow_id),"original_workflow_id":str(original.workflow_id),"amount":str(original.amount)},
                effect=effect,
            ),
            grant,
        )
        return self._result_from_payload(payload)

    def correct(self, *, workflow_id, account, original, corrected_amount, action, approval, grant, connected):
        self._require_uuid(workflow_id,"WorkflowId")
        if original.state!=WorkflowState.RECOGNIZED: raise ValidationError("Only recognized workflow can be corrected")
        if not connected: raise ValidationError("Financial finality requires connectivity")
        corrected_amount=Decimal(str(corrected_amount))
        if corrected_amount<=0 or corrected_amount==original.amount: raise ValidationError("Correction must change the recognized amount")
        delta=corrected_amount-original.amount
        def effect():
            tx,entry=self.recognition.recognize(financial_account_id=account.id,amount=delta)
            return {"workflow_id":str(workflow_id),"transaction_id":str(tx.id),"ledger_entry_id":str(entry.id),"amount":str(delta),"state":WorkflowState.RECOGNIZED.value,"history":list(original.history+(WorkflowEvent(workflow_id,WorkflowState.RECOGNIZED,"workflow_corrected",detail=str(entry.id)).as_dict(),))}
        payload=ApprovalEnforcementAuthority.execute(
            action,approval,
            lambda:self.operations.execute(
                namespace=self.CORRECTION_NAMESPACE,operation_id=str(workflow_id),operation_kind="correct",
                request={"workflow_id":str(workflow_id),"original_workflow_id":str(original.workflow_id),"corrected_amount":str(corrected_amount)},
                effect=effect,
            ),
            grant,
        )
        return self._result_from_payload(payload)

    @staticmethod
    def derive_balance(account,entries):
        return Balance.derive(account.id,entries)
