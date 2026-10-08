import threading
from decimal import Decimal
from pathlib import Path
import tempfile
from uuid import uuid4

import pytest

from agent_core.domain_authorization import Agent, AgentAction, Approval, AuthorizationGrant
from agent_core.domain_finance import (
    Balance, FinancialAccount, LedgerEntry, Obligation, Payment, Settlement
)
from agent_core.financial_orchestration import FinancialOrchestrator, WorkflowState
from agent_core.integrity import DurableOperationAuthority
from agent_core.shared import ValidationError


def _authorized(action_name="financial.recognize"):
    agent = Agent("finance-agent")
    action = AgentAction(agent.id, action_name)
    grant = AuthorizationGrant(agent.id, action_name, "finance-scope")
    grant.activate()
    action.bind_authorization_grant(grant)
    approval = Approval(action.id)
    approval.approve()
    return action, grant, approval


def _workflow(amount="40"):
    account = FinancialAccount(uuid4())
    obligation = Obligation(account.id, Decimal("100"))
    obligation.open()
    payment = Payment(Decimal(amount), obligation_id=obligation.id)
    payment.complete(connected=True)
    settlement = Settlement(payment.id, obligation.id)
    settlement.settle(connected=True)
    return account, obligation, payment, settlement


def _orchestrator(path=None):
    if path is None:
        path = tempfile.mktemp(suffix=".sqlite3")
    return FinancialOrchestrator(DurableOperationAuthority(path))


def test_valid_complete_workflow_and_derived_balance():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    result = _orchestrator().recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    assert result.state is WorkflowState.RECOGNIZED
    assert result.amount == Decimal("40")
    assert obligation.state.value == "satisfied"
    entry = LedgerEntry.__new__(LedgerEntry)
    object.__setattr__(entry, "financial_account_id", account.id)
    object.__setattr__(entry, "amount", Decimal("40"))
    object.__setattr__(entry, "transaction_id", result.transaction_id)
    object.__setattr__(entry, "id", result.ledger_entry_id)
    balance = Balance.derive(account.id, [entry])
    assert balance.amount == Decimal("40")


@pytest.mark.parametrize("case", ["missing_grant", "wrong_grant", "missing_approval", "wrong_approval"])
def test_authorization_and_approval_fail_closed(case):
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    if case == "missing_grant":
        grant = None
    elif case == "wrong_grant":
        other = AuthorizationGrant(action.agent_id, action.action, "other")
        other.activate()
        grant = other
    elif case == "missing_approval":
        approval = None
    else:
        other = AgentAction(action.agent_id, action.action)
        other.bind_authorization_grant(grant)
        approval = Approval(other.id)
        approval.approve()
    with pytest.raises(ValidationError):
        _orchestrator().recognize(
            workflow_id=uuid4(), payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=True,
        )
    assert obligation.state.value != "satisfied"


@pytest.mark.parametrize("transition", ["suspend", "revoke", "expire"])
def test_non_active_grant_states_have_zero_financial_effect(transition):
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    getattr(grant, transition)()
    with pytest.raises(ValidationError):
        _orchestrator().recognize(
            workflow_id=uuid4(), payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=True,
        )
    assert obligation.state.value != "satisfied"


@pytest.mark.parametrize("mismatch", ["payment", "obligation", "payment_obligation"])
def test_payment_settlement_obligation_matching_fails_closed(mismatch):
    account, obligation, payment, settlement = _workflow()
    other_account, other_obligation, other_payment, _ = _workflow("20")
    if mismatch == "payment":
        payment = other_payment
    elif mismatch == "obligation":
        obligation = other_obligation
    else:
        payment = Payment(Decimal("20"), obligation_id=other_obligation.id)
        payment.complete(connected=True)
    action, grant, approval = _authorized()
    with pytest.raises(ValidationError):
        _orchestrator().recognize(
            workflow_id=uuid4(), payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=True,
        )
    assert account.state.value == "active"


def test_invalid_lifecycle_and_offline_finality_are_rejected(tmp_path):
    account, obligation, payment, settlement = _workflow()
    payment.state = type(payment.state).PENDING
    action, grant, approval = _authorized()
    with pytest.raises(ValidationError):
        _orchestrator().recognize(
            workflow_id=uuid4(), payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=True,
        )
    payment.complete(connected=True)
    with pytest.raises(ValidationError):
        _orchestrator(str(tmp_path / "offline.sqlite3")).recognize(
            workflow_id=uuid4(), payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=False,
        )
    assert obligation.state.value != "satisfied"


def test_workflow_id_conflict_has_zero_effect():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    workflow_id = uuid4()
    first = _orchestrator().recognize(
        workflow_id=workflow_id, payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    other_account, other_obligation, other_payment, other_settlement = _workflow("20")
    action2, grant2, approval2 = _authorized()
    with pytest.raises(ValidationError, match="conflict"):
        _orchestrator().recognize(
            workflow_id=workflow_id, payment=other_payment,
            settlement=other_settlement, obligation=other_obligation,
            account=other_account, action=action2, approval=approval2,
            grant=grant2, connected=True,
        )
    assert first.amount == Decimal("40")
    assert other_obligation.state.value != "satisfied"


def test_same_workflow_same_fingerprint_is_idempotent():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    workflow_id = uuid4()
    store = Path("/tmp/stage12a-idempotency.sqlite3")
    if store.exists():
        store.unlink()
    orchestrator = _orchestrator(str(store))
    first = orchestrator.recognize(
        workflow_id=workflow_id, payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    second = orchestrator.recognize(
        workflow_id=workflow_id, payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    assert second == first
    assert second.ledger_entry_id == first.ledger_entry_id
    store.unlink()


def test_restart_retry_reuses_gap5_result_without_duplicate_effect(tmp_path):
    path = str(tmp_path / "gap5.sqlite3")
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    workflow_id = uuid4()
    first = _orchestrator(path).recognize(
        workflow_id=workflow_id, payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    restarted = _orchestrator(path)
    second = restarted.recognize(
        workflow_id=workflow_id, payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    assert second == first
    assert second.ledger_entry_id == first.ledger_entry_id


def test_concurrent_same_workflow_has_exactly_one_semantic_recognition(tmp_path):
    path = str(tmp_path / "concurrent.sqlite3")
    account, obligation, payment, settlement = _workflow()
    workflow_id = uuid4()
    results, errors = [], []
    barrier = threading.Barrier(2)

    def run():
        action, grant, approval = _authorized()
        barrier.wait()
        try:
            results.append(_orchestrator(path).recognize(
                workflow_id=workflow_id, payment=payment, settlement=settlement,
                obligation=obligation, account=account, action=action,
                approval=approval, grant=grant, connected=True,
            ))
        except ValidationError as exc:
            errors.append(str(exc))

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads: thread.start()
    for thread in threads: thread.join()
    assert len(results) == 2
    assert not errors
    assert results[0].ledger_entry_id == results[1].ledger_entry_id
    assert results[0].transaction_id == results[1].transaction_id
    assert obligation.state.value == "satisfied"


def test_direct_ledger_creation_is_rejected():
    with pytest.raises(ValidationError, match="Finance Recognition Command"):
        LedgerEntry(uuid4(), Decimal("10"), uuid4())


def test_duplicate_ledger_attempt_cannot_bypass_finance_command():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    result = _orchestrator().recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    with pytest.raises(ValidationError):
        LedgerEntry(account.id, Decimal("40"), result.transaction_id)
    assert result.state is WorkflowState.RECOGNIZED


def test_reversal_preserves_original_history():
    account, obligation, payment, settlement = _workflow()
    orchestrator = _orchestrator()
    action, grant, approval = _authorized()
    original = orchestrator.recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    reverse_action, reverse_grant, reverse_approval = _authorized("financial.reverse")
    reversed_result = orchestrator.reverse(
        workflow_id=uuid4(), account=account, original=original,
        action=reverse_action, approval=reverse_approval,
        grant=reverse_grant, connected=True,
    )
    assert reversed_result.state is WorkflowState.REVERSED
    assert reversed_result.amount == Decimal("-40")
    assert reversed_result.history[: len(original.history)] == original.history
    assert any(event["event"] == "workflow_reversed" for event in reversed_result.history)


def test_correction_preserves_original_history():
    account, obligation, payment, settlement = _workflow()
    orchestrator = _orchestrator()
    action, grant, approval = _authorized()
    original = orchestrator.recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    correction_action, correction_grant, correction_approval = _authorized("financial.correct")
    corrected = orchestrator.correct(
        workflow_id=uuid4(), account=account, original=original,
        corrected_amount=Decimal("50"), action=correction_action,
        approval=correction_approval, grant=correction_grant, connected=True,
    )
    assert corrected.amount == Decimal("10")
    assert corrected.history[: len(original.history)] == original.history
    assert any(event["event"] == "workflow_corrected" for event in corrected.history)


def test_balance_is_derived_only():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    result = _orchestrator().recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    transaction_entry = object.__new__(LedgerEntry)
    object.__setattr__(transaction_entry, "financial_account_id", account.id)
    object.__setattr__(transaction_entry, "amount", result.amount)
    object.__setattr__(transaction_entry, "transaction_id", result.transaction_id)
    object.__setattr__(transaction_entry, "id", result.ledger_entry_id)
    assert Balance.derive(account.id, [transaction_entry]).amount == result.amount

def test_reversal_replay_is_idempotent(tmp_path):
    account, obligation, payment, settlement = _workflow()
    orchestrator = _orchestrator(str(tmp_path / "reversal.sqlite3"))
    action, grant, approval = _authorized()
    original = orchestrator.recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    reversal_workflow = uuid4()
    action2, grant2, approval2 = _authorized("financial.reverse")
    first = orchestrator.reverse(
        workflow_id=reversal_workflow, account=account, original=original,
        action=action2, approval=approval2, grant=grant2, connected=True,
    )
    second = orchestrator.reverse(
        workflow_id=reversal_workflow, account=account, original=original,
        action=action2, approval=approval2, grant=grant2, connected=True,
    )
    assert second == first
    assert second.state is WorkflowState.REVERSED


def test_correction_replay_is_idempotent(tmp_path):
    account, obligation, payment, settlement = _workflow()
    orchestrator = _orchestrator(str(tmp_path / "correction.sqlite3"))
    action, grant, approval = _authorized()
    original = orchestrator.recognize(
        workflow_id=uuid4(), payment=payment, settlement=settlement,
        obligation=obligation, account=account, action=action,
        approval=approval, grant=grant, connected=True,
    )
    correction_workflow = uuid4()
    action2, grant2, approval2 = _authorized("financial.correct")
    first = orchestrator.correct(
        workflow_id=correction_workflow, account=account, original=original,
        corrected_amount=Decimal("50"), action=action2,
        approval=approval2, grant=grant2, connected=True,
    )
    second = orchestrator.correct(
        workflow_id=correction_workflow, account=account, original=original,
        corrected_amount=Decimal("50"), action=action2,
        approval=approval2, grant=grant2, connected=True,
    )
    assert second == first
    assert second.amount == Decimal("10")


def test_invalid_workflow_id_is_rejected():
    account, obligation, payment, settlement = _workflow()
    action, grant, approval = _authorized()
    with pytest.raises(ValidationError, match="WorkflowId"):
        _orchestrator().recognize(
            workflow_id="not-a-uuid", payment=payment, settlement=settlement,
            obligation=obligation, account=account, action=action,
            approval=approval, grant=grant, connected=True,
        )
    assert obligation.state.value != "satisfied"
