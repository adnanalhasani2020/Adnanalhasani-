from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_identity import *
from agent_core.domain_activities import *
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
    """Create a persisted Sale after validating its offering/activity and delegated authorization.

    The authorization callback must call the project's authoritative authorization
    policy. This application layer deliberately does not invent role or inventory rules.
    """

    def create_sale(
        self,
        connection,
        offering_id: UUID | str,
        activity_id: UUID | str,
        actor_context_ref: str,
        authorize,
        *,
        now: datetime | None = None,
        sale_id: UUID | None = None,
    ) -> Sale:
        try:
            offering_key = str(UUID(str(offering_id)))
            activity_key = str(UUID(str(activity_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Sale requires valid Offering and Activity identifiers") from exc
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Sale creation requires actor context")
        if not callable(authorize):
            raise ValidationError("Sale creation requires authoritative authorization")

        instant = now or datetime.now(timezone.utc)
        if instant.tzinfo is None:
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

        if authorize(actor_context_ref, activity_key, offering_key) is not True:
            raise ValidationError("Sale creation is not authorized for this Activity and Offering")

        identifier = sale_id or uuid4()
        sale = Sale(UUID(offering_key), UUID(activity_key), id=identifier)
        history_ref = f"sale-state:{sale.id}:v1"

        # One transaction: either Sale and its initial history both persist, or neither does.
        with connection:
            connection.execute(
                "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,?,?)",
                (str(sale.id), offering_key, activity_key, SaleState.INITIATED.value, timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(sale.id), "sale_state_transition", timestamp,
                 actor_context_ref, None, history_ref, timestamp),
            )
        return sale
