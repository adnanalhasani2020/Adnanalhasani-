from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_identity import *
from agent_core.domain_activities import *
from agent_core.domain_commerce import Sale, SaleState
from agent_core.inventory_read import InventoryQuantityReader, InventoryQuantityResult
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


class InventoryApplication:
    """Read persisted inventory quantity in the exact Offering and Activity context.

    This is read-only: it does not reserve, deduct, or mutate inventory or Sale.
    """

    def __init__(self, quantity_reader=None):
        self._quantity_reader = quantity_reader or InventoryQuantityReader()

    def read_offering_quantity(
        self, connection, offering_id, scope_key: str, *,
        as_of: datetime | str | None = None,
    ) -> InventoryQuantityResult:
        try:
            offering_key = str(UUID(str(offering_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Offering identifier must be a valid UUID") from exc

        offering = connection.execute(
            "SELECT activity_id FROM offerings WHERE offering_id=?",
            (offering_key,),
        ).fetchone()
        if offering is None:
            raise ValidationError("Inventory quantity read requires an existing Offering")

        # Filter in SQL before resolving latest observation: records from another
        # Offering or Activity cannot create a false quantity or ambiguity.
        return self._quantity_reader.read_quantity(
            connection, scope_key, as_of=as_of,
            offering_id=offering_key, activity_id=offering[0],
        )


class SaleApplication:
    """Application service for authorized Sale lifecycle changes.

    Each transition requires a persisted active AuthorizationGrant with the exact
    actor, action, Sale scope, Activity context, and effective period. This service
    does not create payment, settlement, ledger, or inventory effects.
    """

    _TRANSITIONS = {
        "confirm": ("confirm_sale", SaleState.CONFIRMED, (SaleState.INITIATED,)),
        "fulfill": ("fulfill_sale", SaleState.FULFILLED, (SaleState.CONFIRMED,)),
        "cancel": ("cancel_sale", SaleState.CANCELLED, (SaleState.INITIATED, SaleState.CONFIRMED)),
        "return": ("return_sale", SaleState.RETURNED, (SaleState.FULFILLED,)),
    }

    def _timestamp(self, now):
        instant = now or datetime.now(timezone.utc)
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError("Sale operation timestamp must be timezone-aware")
        return instant.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def _uuid(self, value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    def _grant_instant(self, value, label, owner="AuthorizationGrant"):
        """Parse persisted effective bounds as instants, not lexicographically ordered strings."""
        if not isinstance(value, str):
            raise ValidationError(f"{owner} {label} must be a valid timezone-aware timestamp")
        try:
            instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError(
                f"{owner} {label} must be a valid timezone-aware timestamp"
            ) from exc
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError(
                f"{owner} {label} must be a valid timezone-aware timestamp"
            )
        return instant.astimezone(timezone.utc)

    def _authorize(self, connection, grant_id, actor_id, action, sale_id, activity_id, timestamp):
        grant_key = str(self._uuid(grant_id, "AuthorizationGrant identifier"))
        row = connection.execute(
            "SELECT subject_person_id, agent_id, action_code, scope_ref, context_ref, "
            "state, effective_from, effective_to FROM authorization_grants "
            "WHERE authorization_grant_id=?",
            (grant_key,),
        ).fetchone()
        if row is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        subject, agent_id, action_code, scope_ref, context_ref, state, starts, ends = row
        if state != "active":
            raise ValidationError("AuthorizationGrant must be ACTIVE")
        if subject != str(actor_id) or agent_id is not None:
            raise ValidationError("AuthorizationGrant actor does not match the requested Person")
        if action_code != action:
            raise ValidationError(f"AuthorizationGrant does not permit action {action}")
        if scope_ref != str(sale_id):
            raise ValidationError("AuthorizationGrant scope does not match Sale")
        if context_ref != str(activity_id):
            raise ValidationError("AuthorizationGrant context does not match Activity")
        operation_instant = self._grant_instant(timestamp, "operation timestamp")
        starts_instant = self._grant_instant(starts, "effective_from")
        ends_instant = self._grant_instant(ends, "effective_to") if ends is not None else None
        if starts_instant > operation_instant or (
            ends_instant is not None and operation_instant >= ends_instant
        ):
            raise ValidationError("AuthorizationGrant is outside its effective period")

    def _load_sale(self, connection, sale_id):
        row = connection.execute(
            "SELECT sale_id, offering_id, activity_id, state, lifecycle_state, version_no "
            "FROM sales WHERE sale_id=?",
            (str(sale_id),),
        ).fetchone()
        if row is None:
            raise ValidationError("Sale does not exist")
        persisted_id, offering_id, activity_id, legacy_state, lifecycle_state, version_no = row
        state = SaleState.from_persisted(legacy_state, lifecycle_state)
        history_rows = connection.execute(
            "SELECT actor_context_ref, prior_version_ref, current_version_ref, change_payload_ref "
            "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
            "AND change_type='sale_state_transition' ORDER BY rowid",
            (persisted_id,),
        ).fetchall()
        if not history_rows:
            raise ValidationError("Sale history is missing; refusing an unaudited transition")
        states = []
        previous_ref = None
        for index, (_, prior_ref, current_ref, payload_ref) in enumerate(history_rows, start=1):
            expected_prior = None if index == 1 else history_rows[index - 2][2]
            if prior_ref != expected_prior or current_ref is None:
                raise ValidationError("Sale history chain is inconsistent")
            if index > 1 and prior_ref != previous_ref:
                raise ValidationError("Sale history chain is inconsistent")
            previous_ref = current_ref
            if not payload_ref or not payload_ref.startswith("state:"):
                raise ValidationError(
                    "Sale history lacks transition-state evidence; explicit legacy reconciliation is required"
                )
            states.append(SaleState(payload_ref.removeprefix("state:")))
        if states[0] is not SaleState.INITIATED or states[-1] is not state:
            raise ValidationError("Sale history does not agree with the persisted current state")
        sale = Sale(
            UUID(persisted_id), UUID(activity_id), id=UUID(persisted_id),
            state=state, history=tuple(states),
        )
        return sale, UUID(offering_id), version_no, history_rows[-1][2]

    def create_sale(
        self, connection, offering_id: UUID | str, activity_id: UUID | str,
        actor_context_ref: str, authorization_grant_id: UUID | str, *,
        now: datetime | None = None, sale_id: UUID | None = None,
    ) -> Sale:
        offering_uuid = self._uuid(offering_id, "Offering identifier")
        activity_uuid = self._uuid(activity_id, "Activity identifier")
        actor_uuid = self._uuid(actor_context_ref, "Actor identifier")
        timestamp = self._timestamp(now)
        offering_key, activity_key, actor_key = map(str, (offering_uuid, activity_uuid, actor_uuid))
        self._authorize(
            connection, authorization_grant_id, actor_key, "create_sale",
            offering_key, activity_key, timestamp,
        )
        offering = connection.execute(
            "SELECT o.activity_id, o.state, o.effective_from, o.effective_to, p.state, a.state "
            "FROM offerings AS o JOIN products AS p ON p.product_id=o.product_id "
            "JOIN activities AS a ON a.activity_id=o.activity_id WHERE o.offering_id=?",
            (offering_key,),
        ).fetchone()
        if offering is None:
            raise ValidationError("Sale requires an existing Offering")
        offering_activity, offering_state, effective_from, effective_to, product_state, activity_state = offering
        if offering_activity != activity_key:
            raise ValidationError("Sale Activity must match Offering Activity")
        if offering_state != "active" or product_state != "active" or activity_state != "active":
            raise ValidationError("Sale requires an active Offering, Product, and Activity")
        operation_instant = self._grant_instant(timestamp, "operation timestamp", owner="Offering")
        starts_instant = self._grant_instant(effective_from, "effective_from", owner="Offering")
        ends_instant = (
            self._grant_instant(effective_to, "effective_to", owner="Offering")
            if effective_to is not None else None
        )
        if starts_instant > operation_instant or (
            ends_instant is not None and operation_instant >= ends_instant
        ):
            raise ValidationError("Offering is outside its effective period")

        identifier = sale_id or uuid4()
        sale = Sale(offering_uuid, activity_uuid, id=identifier)
        history_ref = f"sale-state:{sale.id}:v1"
        with connection:
            connection.execute(
                "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
                "VALUES(?,?,?,?,?,?,?,1)",
                (str(sale.id), offering_key, activity_key, SaleState.INITIATED.value,
                 timestamp, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(sale.id), "sale_state_transition", timestamp,
                 actor_key, None, history_ref, "state:initiated", timestamp),
            )
        return sale

    def transition_sale(
        self, connection, sale_id: UUID | str, actor_context_ref: str,
        authorization_grant_id: UUID | str, action: str, *,
        now: datetime | None = None,
    ) -> Sale:
        if action not in self._TRANSITIONS:
            raise ValidationError("Unsupported Sale lifecycle action")
        sale_uuid = self._uuid(sale_id, "Sale identifier")
        actor_uuid = self._uuid(actor_context_ref, "Actor identifier")
        timestamp = self._timestamp(now)
        with connection:
            sale, offering_uuid, version_no, previous_ref = self._load_sale(connection, sale_uuid)
            grant_action, target_state, allowed_from = self._TRANSITIONS[action]
            self._authorize(
                connection, authorization_grant_id, str(actor_uuid), grant_action,
                str(sale.id), str(sale.activity_id), timestamp,
            )
            if sale.state not in allowed_from:
                raise ValidationError(
                    f"Invalid Sale transition from {sale.state.value} via {action}"
                )
            transition = {
                "confirm": sale.confirm,
                "fulfill": sale.complete,
                "cancel": sale.cancel,
                "return": sale.return_sale,
            }[action]
            transition()
            if sale.state is not target_state:
                raise ValidationError("Domain transition did not produce expected state")
            next_version = version_no + 1
            current_ref = f"sale-state:{sale.id}:v{next_version}"
            stored_state = "completed" if target_state is SaleState.FULFILLED else target_state.value
            connection.execute(
                "UPDATE sales SET state=?, updated_at=?, version_no=? WHERE sale_id=? AND version_no=?",
                (stored_state, timestamp, next_version, str(sale.id), version_no),
            )
            if connection.execute("SELECT changes()").fetchone()[0] != 1:
                raise ValidationError("Concurrent Sale update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(sale.id), "sale_state_transition", timestamp,
                 str(actor_uuid), previous_ref, current_ref, f"state:{target_state.value}", timestamp),
            )
        return sale

    def confirm_sale(self, connection, sale_id, actor_context_ref, authorization_grant_id, *, now=None):
        return self.transition_sale(connection, sale_id, actor_context_ref, authorization_grant_id, "confirm", now=now)

    def fulfill_sale(self, connection, sale_id, actor_context_ref, authorization_grant_id, *, now=None):
        return self.transition_sale(connection, sale_id, actor_context_ref, authorization_grant_id, "fulfill", now=now)

    def cancel_sale(self, connection, sale_id, actor_context_ref, authorization_grant_id, *, now=None):
        return self.transition_sale(connection, sale_id, actor_context_ref, authorization_grant_id, "cancel", now=now)

    def return_sale(self, connection, sale_id, actor_context_ref, authorization_grant_id, *, now=None):
        return self.transition_sale(connection, sale_id, actor_context_ref, authorization_grant_id, "return", now=now)

    def get_sale_state(self, connection, sale_id) -> SaleState:
        """Read current canonical state, including legacy completed rows with NULL lifecycle_state."""
        sale_uuid = self._uuid(sale_id, "Sale identifier")
        row = connection.execute(
            "SELECT state, lifecycle_state FROM sales WHERE sale_id=?",
            (str(sale_uuid),),
        ).fetchone()
        if row is None:
            raise ValidationError("Sale does not exist")
        return SaleState.from_persisted(row[0], row[1])

    def get_sale(self, connection, sale_id) -> Sale:
        """Restore a fully verifiable Sale history; fail closed on unprovable legacy chains."""
        sale_uuid = self._uuid(sale_id, "Sale identifier")
        sale, _offering_id, _version, _ref = self._load_sale(connection, sale_uuid)
        return sale
