from typing import Iterable
from agent_core.shared import ValidationError

def reject(reason: str) -> None:
    if not reason or not reason.strip(): raise ValidationError("Rejection reason is required")
    raise ValidationError(reason)

def is_duplicate(operation_key: str, existing_keys: Iterable[str]) -> bool:
    if not operation_key or not operation_key.strip(): raise ValidationError("Duplicate detection key is required")
    return operation_key in set(existing_keys)

def require_owner_domain_decision(owner_domain: str, decision: str | None) -> None:
    if not owner_domain or not owner_domain.strip(): raise ValidationError("Owner domain is required")
    if decision is None:
        raise ValidationError("Owner Domain decision is required")

def preserve_history_on_cancellation() -> bool:
    return True

def preserve_original_on_reversal() -> bool:
    return True
