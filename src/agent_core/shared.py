from enum import Enum
from uuid import UUID, uuid4
class ValidationError(ValueError): pass

def normalize_currency_code(value: str, *, label: str = "currency_code") -> str:
    """Return a canonical ISO-style code representation without asserting assignment.

    Supported representation is exactly three ASCII letters. This intentionally
    does not decide which currencies or markets the product supports.
    """
    if not isinstance(value, str):
        raise ValidationError(f"{label} must be a three-letter ASCII code")
    code = value.strip()
    if len(code) != 3 or not code.isascii() or not code.isalpha():
        raise ValidationError(f"{label} must be a three-letter ASCII code")
    return code.upper()

def require_canonical_currency_code(value: str, *, label: str = "currency_code") -> str:
    """Validate an already-canonical persisted/API code; do not rewrite it."""
    if (not isinstance(value, str) or len(value) != 3 or not value.isascii()
            or not value.isalpha() or value != value.upper()):
        raise ValidationError(f"{label} must be a 3-letter uppercase code")
    return value

def new_id() -> UUID: return uuid4()
class PersonState(str,Enum): ACTIVE="active"; INACTIVE="inactive"; CORRECTED="corrected"
class IdentifierState(str,Enum): ISSUED="issued"; ACTIVE="active"; EXPIRED="expired"; REVOKED="revoked"
class AccessAccountState(str,Enum): ACTIVE="active"; SUSPENDED="suspended"; EXPIRED="expired"; RECOVERY="recovery"
class AuthenticatorState(str,Enum): ENROLLED="enrolled"; ACTIVE="active"; REVOKED="revoked"; EXPIRED="expired"
class SessionState(str,Enum): STARTED="started"; ACTIVE="active"; EXPIRED="expired"; CANCELLED="cancelled"
class ActivityState(str,Enum): ESTABLISHED="established"; ACTIVE="active"; SUSPENDED="suspended"; ENDED="ended"
class OrganizationState(str,Enum): ESTABLISHED="established"; ACTIVE="active"; DISSOLVED="dissolved"
class MembershipState(str,Enum): PROPOSED="proposed"; ACTIVE="active"; SUSPENDED="suspended"; ENDED="ended"
class RoleAssignmentState(str,Enum): ASSIGNED="assigned"; ACTIVE="active"; REVOKED="revoked"; EXPIRED="expired"
