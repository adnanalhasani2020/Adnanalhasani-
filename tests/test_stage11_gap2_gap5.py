import threading
from uuid import uuid4

import pytest

from agent_core.domain_audit import Provenance
from agent_core.integrity import (
    DurableOperationAuthority,
    RuntimeIntegrityGate,
    SemanticAuthority,
    SemanticReference,
)
from agent_core.shared import ValidationError


def test_gap2_existing_entity_is_resolved_and_owned():
    authority = SemanticAuthority()
    entity_id = uuid4()
    authority.register_entity("Person", entity_id, "Identity")
    resolved = authority.resolve(SemanticReference("Person", entity_id, "Identity"))
    assert resolved.entity_id == entity_id


def test_gap2_nonexistent_entity_is_rejected():
    authority = SemanticAuthority()
    with pytest.raises(ValidationError, match="does not exist"):
        authority.resolve(SemanticReference("Person", uuid4(), "Identity"))


def test_gap2_valid_uuid_type_but_semantically_absent_is_rejected():
    authority = SemanticAuthority()
    with pytest.raises(ValidationError):
        authority.resolve(SemanticReference("Person", uuid4(), "Identity"))


def test_gap2_wrong_ownership_is_rejected():
    authority = SemanticAuthority()
    entity_id = uuid4()
    authority.register_entity("Person", entity_id, "Identity")
    with pytest.raises(ValidationError, match="ownership"):
        authority.resolve(SemanticReference("Person", entity_id, "Finance"))


def test_gap2_required_provenance_valid():
    authority = SemanticAuthority()
    entity_id = uuid4()
    authority.register_entity("Person", entity_id, "Identity")
    provenance = Provenance(f"Person:{entity_id}", "Person")
    authority.register_provenance(provenance)
    resolved = authority.resolve(
        SemanticReference("Person", entity_id, "Identity"),
        provenance=provenance,
        provenance_required=True,
    )
    assert resolved.entity_id == entity_id


@pytest.mark.parametrize("provenance", [None, Provenance("wrong", "Person")])
def test_gap2_required_provenance_missing_or_invalid_is_rejected(provenance):
    authority = SemanticAuthority()
    entity_id = uuid4()
    authority.register_entity("Person", entity_id, "Identity")
    if provenance is not None:
        authority.register_provenance(provenance)
    with pytest.raises(ValidationError):
        authority.resolve(
            SemanticReference("Person", entity_id, "Identity"),
            provenance=provenance,
            provenance_required=True,
        )


def test_gap2_and_gap5_share_one_runtime_gate(tmp_path):
    semantic = SemanticAuthority()
    entity_id = uuid4()
    semantic.register_entity("Person", entity_id, "Identity")
    gate = RuntimeIntegrityGate(
        semantic, DurableOperationAuthority(str(tmp_path / "operations.sqlite3"))
    )
    calls = []
    assert gate.execute(
        reference=SemanticReference("Person", entity_id, "Identity"),
        namespace="identity",
        operation_id="op-gate",
        operation_kind="attach",
        request={"value": "x"},
        effect=lambda: calls.append("effect") or {"ok": True},
    ) == {"ok": True}
    assert calls == ["effect"]


def test_gap5_first_operation_and_exact_duplicate(tmp_path):
    store = DurableOperationAuthority(str(tmp_path / "operations.sqlite3"))
    calls = []
    request = {"amount": 10, "currency": "USD"}

    first = store.execute(
        namespace="payments",
        operation_id="op-1",
        operation_kind="charge",
        request=request,
        effect=lambda: calls.append("effect") or {"receipt": "r1"},
    )
    second = store.execute(
        namespace="payments",
        operation_id="op-1",
        operation_kind="charge",
        request=request,
        effect=lambda: calls.append("duplicate-effect") or {"receipt": "r2"},
    )

    assert first == second == {"receipt": "r1"}
    assert calls == ["effect"]


def test_gap5_replay_after_restart(tmp_path):
    path = str(tmp_path / "operations.sqlite3")
    first = DurableOperationAuthority(path)
    assert first.execute(
        namespace="payments",
        operation_id="op-restart",
        operation_kind="charge",
        request={"amount": 20},
        effect=lambda: {"receipt": "restart-safe"},
    ) == {"receipt": "restart-safe"}

    restarted = DurableOperationAuthority(path)
    calls = []
    assert restarted.execute(
        namespace="payments",
        operation_id="op-restart",
        operation_kind="charge",
        request={"amount": 20},
        effect=lambda: calls.append("unexpected") or {"receipt": "new"},
    ) == {"receipt": "restart-safe"}
    assert calls == []


def test_gap5_conflicting_reuse_is_rejected_and_does_not_execute(tmp_path):
    store = DurableOperationAuthority(str(tmp_path / "operations.sqlite3"))
    calls = []
    store.execute(
        namespace="payments",
        operation_id="op-conflict",
        operation_kind="charge",
        request={"amount": 20},
        effect=lambda: {"receipt": "r1"},
    )
    with pytest.raises(ValidationError, match="conflict"):
        store.execute(
            namespace="payments",
            operation_id="op-conflict",
            operation_kind="refund",
            request={"amount": 20},
            effect=lambda: calls.append("unexpected") or {"receipt": "r2"},
        )
    assert calls == []


def test_gap5_idempotent_repeated_submission(tmp_path):
    store = DurableOperationAuthority(str(tmp_path / "operations.sqlite3"))
    counter = {"effects": 0}

    def effect():
        counter["effects"] += 1
        return {"accepted": True}

    for _ in range(3):
        assert store.execute(
            namespace="actions",
            operation_id="op-idempotent",
            operation_kind="execute",
            request={"action": "review"},
            effect=effect,
        ) == {"accepted": True}
    assert counter["effects"] == 1


def test_gap5_atomic_uniqueness_behavior(tmp_path):
    path = str(tmp_path / "operations.sqlite3")
    first = DurableOperationAuthority(path)
    second = DurableOperationAuthority(path)
    outcomes = []
    effects = []
    barrier = threading.Barrier(2)

    def effect():
        effects.append("semantic-effect")
        return {"ok": True}

    def submit(authority):
        barrier.wait()
        try:
            outcomes.append(
                authority.execute(
                    namespace="actions",
                    operation_id="op-atomic",
                    operation_kind="execute",
                    request={"action": "review"},
                    effect=effect,
                )
            )
        except ValidationError as exc:
            outcomes.append(str(exc))

    t1 = threading.Thread(target=submit, args=(first,))
    t2 = threading.Thread(target=submit, args=(second,))
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    assert outcomes.count({"ok": True}) == 2
    assert len(effects) == 1
    assert len(outcomes) == 2
    assert any("replay is incomplete" in str(item) or item == {"ok": True} for item in outcomes)
