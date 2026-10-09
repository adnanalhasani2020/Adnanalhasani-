from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_identity import *
from agent_core.domain_activities import *
from agent_core.domain_authorization import AuthorizationGrant, AuthorizationGrantState
from agent_core.domain_commerce import Sale, SaleState
from agent_core.shared import ValidationError


class IdentityApplication:
    def create_person(self): return Person()
    def attach_identifier(self,p,value): return Identifier(p.id,value)
    def create_access_account(self,p): return AccessAccount(p.id)
    def enroll_authenticator(self,a): return Authenticator(a.id)
    def start_session(self,a):
        if a.state.value!="active": raise ValidationError("Session requires active Access Account")
        return Session(a.id)


class ActivityApplication:
    def create_activity(self,name): return Activity(name)
    def create_organization(self,name): return Organization(name)
    def add_membership(self,p,a): return Membership(p.id,a.id)
    def assign_role(self,m,role_name): return RoleAssignment(m.id,role_name)


class SaleApplication:
    """Create a Sale using the repository's existing explicit AuthorizationGrant model.

    Authorization is fail-closed and scoped to the exact actor, create_sale action,
    and Activity. The Offering must independently belong to that Activity. No role,
    membership, family relationship, payment, settlement, or inventory policy is inferred.
    """

    def create_sale(
        self,
        connection,
        offering_id: UUID | str,
        activity_id: UUID | str,
        actor_context_ref: str,
        authorization: AuthorizationGrant,
        *,
        now: datetime | None = None,
        sale_id: UUID | None = None,
    ) -> Sale:
        try:
            offering_uuid = UUID(str(offering_id))
            activity_uuid = UUID(str(activity_id))
            actor_uuid = UUID(str(actor_context_ref))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(
                "Sale creation requires UUID Offering, Activity, and actor context references"
            ) from exc
        offering_key, activity_key, actor_key = (
            str(offering_uuid), str(activity_uuid), str(actor_uuid)
        )

        if not isinstance(authorization, AuthorizationGrant):
            raise ValidationError("Sale creation requires an authoritative AuthorizationGrant")
        if authorization.state is not AuthorizationGrantState.ACTIVE:
            raise ValidationError("Sale creation AuthorizationGrant must be ACTIVE")
        if authorization.subject_id != actor_uuid:
            raise ValidationError("AuthorizationGrant subject does not match actor")
        if authorization.action != "create_sale":
            raise ValidationError("AuthorizationGrant action does not permit Sale creation")
        if authorization.scope != activity_key:
            raise ValidationError("AuthorizationGrant scope does not match requested Activity")

        instant = now or datetime.now(timezone.utc)
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError("Sale creation timestamp must be timezone-aware")
        timestamp = instant.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

        offering = connection.execute(
            "SELECT o.activity_id, o.state, o.effective_from, o.effective_to, p.state, a.state "
            "FROM offerings AS o "
            "JOIN products AS p ON p.product_id = o.product_id "
            "JOIN activities AS a ON a.activity_id = o.activity_id "
            "WHERE o.offering_id = ?",
            (offering_key,),
        ).fetchone()
        if offering is None:
            raise ValidationError("Sale requires an existing Offering")
        offering_activity, offering_state, effective_from, effective_to, product_state, activity_state = offering
        if offering_activity != activity_key:
            raise ValidationError("Sale Activity must match Offering Activity")
        if offering_state != "active" or product_state != "active" or activity_state != "active":
            raise ValidationError("Sale requires an active Offering, Product, and Activity")
        if effective_from > timestamp or (effective_to is not None and timestamp >= effective_to):
            raise ValidationError("Offering is outside its effective period")

        identifier = sale_id or uuid4()
        sale = Sale(offering_uuid, activity_uuid, id=identifier)
        history_ref = f"sale-state:{sale.id}:v1"

        # One transaction: either Sale and initial history both persist, or neither does.
        with connection:
            connection.execute(
                "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,?,?)",
                (str(sale.id), offering_key, activity_key, SaleState.INITIATED.value,
                 timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(sale.id), "sale_state_transition", timestamp,
                 actor_key, None, history_ref, timestamp),
            )
        return sale
