from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Iterable, Optional
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class FinancialAccountState(str, Enum):
    ACTIVE="active"; CLOSED="closed"
class ObligationState(str, Enum):
    PROPOSED="proposed"; OPEN="open"; DUE="due"; OVERDUE="overdue"; SATISFIED="satisfied"; CANCELLED="cancelled"
class LoanState(str, Enum):
    PROPOSED="proposed"; ACTIVE="active"; CLOSED="closed"; CANCELLED="cancelled"
class PaymentState(str, Enum):
    INITIATED="initiated"; PENDING="pending"; COMPLETED="completed"; FAILED="failed"; CANCELLED="cancelled"
class SettlementState(str, Enum):
    PENDING="pending"; SETTLED="settled"; FAILED="failed"; REVERSED="reversed"
class FinancialTransactionState(str, Enum):
    RECOGNIZED="recognized"; VOID="void"

@dataclass
class FinancialAccount:
    person_id: UUID
    id: UUID=field(default_factory=new_id)
    state: FinancialAccountState=FinancialAccountState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.person_id, UUID): raise ValidationError("FinancialAccount must reference Person")
    def close(self): self.state=FinancialAccountState.CLOSED

@dataclass
class Obligation:
    financial_account_id: UUID; amount: Decimal; id: UUID=field(default_factory=new_id)
    state: ObligationState=ObligationState.PROPOSED; due_at: Optional[datetime]=None
    def __post_init__(self):
        if not isinstance(self.financial_account_id, UUID): raise ValidationError("Obligation must reference Financial Account")
        self.amount=Decimal(str(self.amount))
        if self.amount<=0: raise ValidationError("Obligation.amount must be positive")
    def open(self): self.state=ObligationState.OPEN
    def mark_due(self): self.state=ObligationState.DUE
    def mark_overdue(self): self.state=ObligationState.OVERDUE
    def satisfy(self): self.state=ObligationState.SATISFIED
    def cancel(self): self.state=ObligationState.CANCELLED

@dataclass(frozen=True)
class Debt:
    obligation_id: UUID
    def __post_init__(self):
        if not isinstance(self.obligation_id, UUID): raise ValidationError("Debt must classify an Obligation")

@dataclass
class Loan:
    lender_person_id: UUID; borrower_person_id: UUID; id: UUID=field(default_factory=new_id)
    state: LoanState=LoanState.PROPOSED
    def __post_init__(self):
        if not isinstance(self.lender_person_id,UUID) or not isinstance(self.borrower_person_id,UUID): raise ValidationError("Loan must reference Persons")
        if self.lender_person_id==self.borrower_person_id: raise ValidationError("Loan parties must differ")
    def activate(self): self.state=LoanState.ACTIVE
    def close(self): self.state=LoanState.CLOSED
    def cancel(self): self.state=LoanState.CANCELLED

@dataclass
class Payment:
    amount: Decimal; obligation_id: Optional[UUID]=None; invoice_id: Optional[UUID]=None
    id: UUID=field(default_factory=new_id); state: PaymentState=PaymentState.INITIATED
    def __post_init__(self):
        self.amount=Decimal(str(self.amount))
        if self.amount<=0: raise ValidationError("Payment.amount must be positive")
        if self.obligation_id is not None and not isinstance(self.obligation_id,UUID): raise ValidationError("Payment.obligation_id must be UUID")
        if self.invoice_id is not None and not isinstance(self.invoice_id,UUID): raise ValidationError("Payment.invoice_id must be UUID")
    def pending(self): self.state=PaymentState.PENDING
    def complete(self): self.state=PaymentState.COMPLETED
    def fail(self): self.state=PaymentState.FAILED
    def cancel(self): self.state=PaymentState.CANCELLED

@dataclass
class Settlement:
    payment_id: Optional[UUID]=None; obligation_id: Optional[UUID]=None; id: UUID=field(default_factory=new_id)
    state: SettlementState=SettlementState.PENDING
    def __post_init__(self):
        if self.payment_id is None and self.obligation_id is None: raise ValidationError("Settlement requires Payment or Obligation reference")
        if self.payment_id is not None and not isinstance(self.payment_id,UUID): raise ValidationError("Settlement.payment_id must be UUID")
        if self.obligation_id is not None and not isinstance(self.obligation_id,UUID): raise ValidationError("Settlement.obligation_id must be UUID")
    def settle(self): self.state=SettlementState.SETTLED
    def fail(self): self.state=SettlementState.FAILED
    def reverse(self): self.state=SettlementState.REVERSED

@dataclass(frozen=True)
class FinancialTransaction:
    financial_account_id: UUID; amount: Decimal; id: UUID=field(default_factory=new_id)
    state: FinancialTransactionState=FinancialTransactionState.RECOGNIZED
    def __post_init__(self):
        if not isinstance(self.financial_account_id,UUID): raise ValidationError("FinancialTransaction must reference Financial Account")
        object.__setattr__(self,"amount",Decimal(str(self.amount)))
        if self.amount==0: raise ValidationError("FinancialTransaction.amount cannot be zero")

@dataclass(frozen=True)
class LedgerEntry:
    financial_account_id: UUID; amount: Decimal; transaction_id: UUID; id: UUID=field(default_factory=new_id)
    def __post_init__(self):
        if not isinstance(self.financial_account_id,UUID) or not isinstance(self.transaction_id,UUID): raise ValidationError("LedgerEntry references are required")
        object.__setattr__(self,"amount",Decimal(str(self.amount)))
        if self.amount==0: raise ValidationError("LedgerEntry.amount cannot be zero")

@dataclass(frozen=True)
class Balance:
    financial_account_id: UUID; amount: Decimal
    @classmethod
    def derive(cls, account_id: UUID, entries: Iterable[LedgerEntry]):
        if not isinstance(account_id,UUID): raise ValidationError("Balance requires Financial Account")
        total=sum((e.amount for e in entries if e.financial_account_id==account_id),Decimal("0"))
        return cls(account_id,total)
