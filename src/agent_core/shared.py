from enum import Enum
from uuid import UUID, uuid4
class ValidationError(ValueError): pass
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
