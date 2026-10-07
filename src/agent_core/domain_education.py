from uuid import UUID
from agent_core.shared import ValidationError
from agent_core.domain_activities import Membership, RoleAssignment
from agent_core.domain_authorization import FamilyRelationship, AuthorizationGrant

def validate_education_context(
    person_id: UUID,
    membership: Membership,
    role_assignment: RoleAssignment | None = None,
    family_relationship: FamilyRelationship | None = None,
    authorization: AuthorizationGrant | None = None,
) -> bool:
    if not isinstance(person_id, UUID): raise ValidationError("Education context requires Person")
    if membership.person_id != person_id: raise ValidationError("Membership does not belong to Person")
    if role_assignment is not None and role_assignment.membership_id != membership.id:
        raise ValidationError("Role Assignment does not belong to Membership")
    if family_relationship is not None and person_id not in (family_relationship.person_a_id, family_relationship.person_b_id):
        raise ValidationError("Family Relationship does not include Person")
    if authorization is not None and authorization.subject_id != person_id:
        raise ValidationError("Authorization subject does not match Person")
    return True

def education_access_is_authorized(authorization: AuthorizationGrant | None) -> bool:
    return authorization is not None and authorization.state.value == "active"
