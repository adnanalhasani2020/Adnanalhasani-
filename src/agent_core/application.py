from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4
from urllib.parse import quote, unquote

from agent_core.domain_identity import *
from agent_core.domain_activities import *
from agent_core.domain_commerce import Sale, SaleState
from agent_core.domain_inventory import Product, ProductState
from agent_core.inventory_read import InventoryQuantityReader, InventoryQuantityResult
from agent_core.shared import ValidationError


@contextmanager
def _atomic(connection):
    """Own a transaction only when the caller has not already opened one."""
    if connection.in_transaction:
        savepoint = "application_" + uuid4().hex
        connection.execute(f"SAVEPOINT {savepoint}")
        try:
            yield
        except BaseException:
            connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            raise
        else:
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
        return

    connection.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        connection.rollback()
        raise
    else:
        connection.commit()


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
            "SELECT product_id, activity_id FROM offerings WHERE offering_id=?",
            (offering_key,),
        ).fetchone()
        if offering is None:
            raise ValidationError("Inventory quantity read requires an existing Offering")
        product_id, activity_id = offering

        # Migrations preserve legacy rows rather than rewriting their context.
        # Do not let a mismatched persisted position supply a quantity after reopen.
        mismatch = connection.execute(
            "SELECT inventory_position_id FROM inventory_positions "
            "WHERE offering_id=? AND (activity_id<>? OR "
            "(product_id IS NOT NULL AND product_id<>?)) LIMIT 1",
            (offering_key, activity_id, product_id),
        ).fetchone()
        if mismatch is not None:
            raise ValidationError(
                "Inventory quantity read found a position inconsistent with its Offering context; "
                "explicit reconciliation is required"
            )

        # Filter in SQL before resolving latest observation: records from another
        # Offering or Activity cannot create a false quantity or ambiguity.
        return self._quantity_reader.read_quantity(
            connection, scope_key, as_of=as_of,
            offering_id=offering_key, activity_id=activity_id,
        )


    def read_product_quantity(
        self, connection, product_id, activity_id, scope_key: str, *,
        offering_id=None, location_ref=None, as_of: datetime | str | None = None,
    ) -> InventoryQuantityResult:
        """Read a product's own stock scope, excluding unrelated offerings and locations."""
        try:
            product_key = str(UUID(str(product_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Product identifier must be a valid UUID") from exc
        try:
            activity_key = str(UUID(str(activity_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Activity identifier must be a valid UUID") from exc

        if connection.execute(
            "SELECT 1 FROM products WHERE product_id=?", (product_key,)
        ).fetchone() is None:
            raise ValidationError("Inventory quantity read requires an existing Product")
        if connection.execute(
            "SELECT 1 FROM activities WHERE activity_id=?", (activity_key,)
        ).fetchone() is None:
            raise ValidationError("Inventory quantity read requires an existing Activity")

        offering_key = None
        if offering_id is not None:
            try:
                offering_key = str(UUID(str(offering_id)))
            except (ValueError, TypeError, AttributeError) as exc:
                raise ValidationError("Offering identifier must be a valid UUID") from exc
            offering = connection.execute(
                "SELECT product_id, activity_id FROM offerings WHERE offering_id=?",
                (offering_key,),
            ).fetchone()
            if offering is None:
                raise ValidationError("Inventory quantity read requires an existing Offering")
            if offering != (product_key, activity_key):
                raise ValidationError("Inventory quantity read Product/Activity must match Offering")

        if location_ref is not None and (
            not isinstance(location_ref, str) or not location_ref.strip()
        ):
            raise ValidationError("Inventory location_ref must be a non-empty string when supplied")
        normalized_location = location_ref.strip() if location_ref is not None else None
        return self._quantity_reader.read_quantity(
            connection, scope_key, as_of=as_of, product_id=product_key,
            activity_id=activity_key, offering_id=offering_key,
            require_no_offering=offering_key is None,
            location_ref=normalized_location,
            require_no_location=normalized_location is None,
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
        if now is None:
            instant = datetime.now(timezone.utc)
        elif isinstance(now, datetime):
            instant = now
        else:
            raise ValidationError("Sale operation timestamp must be a timezone-aware datetime")
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
            "delegation_id, state, effective_from, effective_to FROM authorization_grants "
            "WHERE authorization_grant_id=?",
            (grant_key,),
        ).fetchone()
        if row is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        (
            subject, agent_id, action_code, scope_ref, context_ref,
            delegation_id, state, starts, ends,
        ) = row
        if state != "active":
            raise ValidationError("AuthorizationGrant must be ACTIVE")
        if subject != str(actor_id) or agent_id is not None:
            raise ValidationError("AuthorizationGrant actor does not match the requested Person")
        person = connection.execute(
            "SELECT state FROM persons WHERE person_id=?",
            (str(actor_id),),
        ).fetchone()
        if person is None or person[0] != "active":
            raise ValidationError("Sale action requires an active persisted Person")
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
        if delegation_id is not None:
            delegation = connection.execute(
                "SELECT subject_person_id, context_ref, state, effective_from, effective_to "
                "FROM delegations WHERE delegation_id=?",
                (delegation_id,),
            ).fetchone()
            if delegation is None:
                raise ValidationError("AuthorizationGrant delegation does not exist")
            delegated_subject, delegation_context, delegation_state, delegation_starts, delegation_ends = delegation
            if delegated_subject != str(actor_id) or delegation_context != str(activity_id):
                raise ValidationError("AuthorizationGrant delegation does not match actor and Activity")
            if delegation_state != "active":
                raise ValidationError("AuthorizationGrant delegation must be ACTIVE")
            delegation_start_instant = self._grant_instant(
                delegation_starts, "effective_from", owner="Delegation"
            )
            delegation_end_instant = (
                self._grant_instant(delegation_ends, "effective_to", owner="Delegation")
                if delegation_ends is not None else None
            )
            if delegation_start_instant > operation_instant or (
                delegation_end_instant is not None and operation_instant >= delegation_end_instant
            ):
                raise ValidationError("Delegation is outside its effective period")

    def _load_sale(self, connection, sale_id):
        row = connection.execute(
            "SELECT sale_id, offering_id, activity_id, state, lifecycle_state, version_no "
            "FROM sales WHERE sale_id=?",
            (str(sale_id),),
        ).fetchone()
        if row is None:
            raise ValidationError("Sale does not exist")
        persisted_id, offering_id, activity_id, legacy_state, lifecycle_state, version_no = row
        # Legacy databases can preserve Sale/Offering context mismatches during migration.
        # Never silently repair them or permit lifecycle operations on a cross-context Sale.
        offering = connection.execute(
            "SELECT activity_id FROM offerings WHERE offering_id=?",
            (offering_id,),
        ).fetchone()
        if offering is None:
            raise ValidationError("Sale references a missing Offering; explicit reconciliation is required")
        if offering[0] != activity_id:
            raise ValidationError(
                "Sale Activity does not match its persisted Offering Activity; "
                "explicit reconciliation is required"
            )
        state = SaleState.from_persisted(legacy_state, lifecycle_state)
        history_rows = connection.execute(
            "SELECT actor_context_ref, prior_version_ref, current_version_ref, change_payload_ref "
            "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
            "AND change_type='sale_state_transition' ORDER BY rowid",
            (persisted_id,),
        ).fetchall()
        if not history_rows:
            raise ValidationError("Sale history is missing; refusing an unaudited transition")
        if len(history_rows) != version_no:
            raise ValidationError(
                "Sale history entry count does not match the persisted Sale version"
            )
        states = []
        previous_ref = None
        for index, (actor_context_ref, prior_ref, current_ref, payload_ref) in enumerate(history_rows, start=1):
            expected_prior = None if index == 1 else history_rows[index - 2][2]
            expected_current = f"sale-state:{persisted_id}:v{index}"
            if not actor_context_ref or not str(actor_context_ref).strip():
                raise ValidationError(
                    "Sale history lacks actor context; explicit legacy reconciliation is required"
                )
            if prior_ref != expected_prior or current_ref != expected_current:
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

        # Transaction ownership is explicit: create our own write transaction only
        # when needed; otherwise isolate this operation with a SAVEPOINT. A sqlite3
        # connection context manager is not a nested transaction and may commit or
        # roll back work owned by the caller.
        owns_transaction = not connection.in_transaction
        savepoint = "sale_create_operation"
        if owns_transaction:
            connection.execute("BEGIN IMMEDIATE")
        savepoint_started = False
        try:
            connection.execute(f"SAVEPOINT {savepoint}")
            savepoint_started = True
            self._authorize(
                connection, authorization_grant_id, actor_key, "create_sale",
                offering_key, activity_key, timestamp,
            )
            offering = connection.execute(
                "SELECT o.activity_id, o.state, o.effective_from, o.effective_to, p.state, a.state, "
                "o.service_id, s.state "
                "FROM offerings AS o JOIN products AS p ON p.product_id=o.product_id "
                "JOIN activities AS a ON a.activity_id=o.activity_id "
                "LEFT JOIN services AS s ON s.service_id=o.service_id WHERE o.offering_id=?",
                (offering_key,),
            ).fetchone()
            if offering is None:
                raise ValidationError("Sale requires an existing Offering")
            (
                offering_activity, offering_state, effective_from, effective_to,
                product_state, activity_state, service_id, service_state,
            ) = offering
            if offering_activity != activity_key:
                raise ValidationError("Sale Activity must match Offering Activity")
            if offering_state != "active" or product_state != "active" or activity_state != "active":
                raise ValidationError("Sale requires an active Offering, Product, and Activity")
            if service_id is not None and service_state != "active":
                raise ValidationError("Sale requires an active Service for a service-backed Offering")
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

            identifier = self._uuid(sale_id, "Sale identifier") if sale_id is not None else uuid4()
            sale = Sale(offering_uuid, activity_uuid, id=identifier)
            history_ref = f"sale-state:{sale.id}:v1"
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
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            savepoint_started = False
            if owns_transaction:
                connection.commit()
        except Exception:
            if savepoint_started and connection.in_transaction:
                connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
                connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            if owns_transaction and connection.in_transaction:
                connection.rollback()
            raise
        return sale

    def transition_sale(
        self, connection, sale_id: UUID | str, actor_context_ref: str,
        authorization_grant_id: UUID | str, action: str, *,
        now: datetime | None = None,
    ) -> Sale:
        if not isinstance(action, str) or action not in self._TRANSITIONS:
            raise ValidationError("Unsupported Sale lifecycle action")
        sale_uuid = self._uuid(sale_id, "Sale identifier")
        actor_uuid = self._uuid(actor_context_ref, "Actor identifier")
        timestamp = self._timestamp(now)

        # Keep failures local to this transition when a caller owns an outer
        # transaction; sqlite3's connection context manager is not a savepoint.
        savepoint = "sale_transition_operation"
        savepoint_started = False
        try:
            connection.execute(f"SAVEPOINT {savepoint}")
            savepoint_started = True
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
            connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            savepoint_started = False
        except Exception:
            if savepoint_started and connection.in_transaction:
                connection.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
                connection.execute(f"RELEASE SAVEPOINT {savepoint}")
            raise
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


class InventoryPositionApplication:
    """Persist InventoryPosition lifecycle without turning observations into stock movements."""

    _TRANSITIONS = {
        "make_effective": ("observed", "effective"),
        "close": ("effective", "closed"),
    }

    def _instant(self, value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc)

    def _validate_offering_context(self, connection, offering_key, activity_key, product_key):
        if offering_key is None:
            return
        offering_context = connection.execute(
            "SELECT product_id, activity_id FROM offerings WHERE offering_id=?",
            (offering_key,),
        ).fetchone()
        if offering_context is None:
            raise ValidationError("Inventory Position requires an existing Offering")
        offering_product_key, offering_activity_key = offering_context
        if offering_activity_key != activity_key:
            raise ValidationError("Inventory Position context must match its offering")
        if product_key is not None and offering_product_key != product_key:
            raise ValidationError("Inventory Position Product must match its Offering Product")

    def _restore_position(self, row):
        from agent_core.domain_inventory import InventoryPosition, InventoryPositionState

        (position_id, activity_id, product_id, offering_id, location_ref, scope_key,
         quantity_minor, observed_at, effective_from, effective_to, state) = row[:11]
        def parse(value):
            return datetime.fromisoformat(value.replace("Z", "+00:00")) if value else None
        return InventoryPosition(
            id=UUID(position_id),
            activity_id=UUID(activity_id),
            product_id=UUID(product_id) if product_id else None,
            offering_id=UUID(offering_id) if offering_id else None,
            location_ref=location_ref,
            scope_key=scope_key,
            quantity_minor=quantity_minor,
            observed_at=parse(observed_at),
            effective_from=parse(effective_from),
            effective_to=parse(effective_to),
            state=InventoryPositionState(state),
        )

    def create_position(
        self, connection, *, activity_id, scope_key, offering_id=None, product_id=None,
        location_ref=None, quantity_minor=None, observed_at=None, effective_from=None,
        effective_to=None, now=None, position_id=None, actor_context_ref=None,
    ):
        from agent_core.domain_inventory import InventoryPosition

        def uuid_key(value, label, optional=False):
            if value is None and optional:
                return None
            try:
                return str(UUID(str(value)))
            except (ValueError, TypeError, AttributeError) as exc:
                raise ValidationError(f"{label} must be a valid UUID") from exc

        activity_key = uuid_key(activity_id, "Activity identifier")
        product_key = uuid_key(product_id, "Product identifier", optional=True)
        offering_key = uuid_key(offering_id, "Offering identifier", optional=True)
        identifier = uuid_key(position_id or uuid4(), "Inventory Position identifier")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Inventory Position actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        timestamp = self._instant(now if now is not None else datetime.now(timezone.utc), "Inventory Position created_at")
        observed = self._instant(observed_at if observed_at is not None else timestamp, "Inventory Position observed_at")
        starts = self._instant(effective_from, "Inventory Position effective_from") if effective_from is not None else None
        ends = self._instant(effective_to, "Inventory Position effective_to") if effective_to is not None else None
        # Domain validation is deliberately performed before persistence.
        result = InventoryPosition(
            id=UUID(identifier), activity_id=UUID(activity_key),
            product_id=UUID(product_key) if product_key else None,
            offering_id=UUID(offering_key) if offering_key else None,
            location_ref=location_ref, scope_key=scope_key, quantity_minor=quantity_minor,
            observed_at=observed, effective_from=starts, effective_to=ends,
        )
        with _atomic(connection):
            self._validate_offering_context(connection, offering_key, activity_key, product_key)
            connection.execute(
                "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,"
                "location_ref,scope_key,state,quantity_minor,observed_at,effective_from,effective_to,"
                "created_at,updated_at,provenance_id,version_no) "
                "VALUES(?,?,?,?,?,?,'observed',?,?,?,?,?,?,NULL,1)",
                (identifier, activity_key, product_key, offering_key, location_ref, scope_key,
                 quantity_minor, observed.isoformat().replace("+00:00", "Z"),
                 starts.isoformat().replace("+00:00", "Z") if starts else None,
                 ends.isoformat().replace("+00:00", "Z") if ends else None,
                 timestamp.isoformat().replace("+00:00", "Z"),
                 timestamp.isoformat().replace("+00:00", "Z")),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "inventory", identifier, "inventory_position_lifecycle",
                 timestamp.isoformat().replace("+00:00", "Z"), actor_context_ref, None,
                 f"inventory_position:{identifier}:v1", "action:create;state:observed",
                 timestamp.isoformat().replace("+00:00", "Z")),
            )
        return result


    def create_opening_stock(
        self, connection, *, activity_id, product_id, scope_key, quantity_minor,
        actor_context_ref, source_ref, idempotency_key, opening_at,
        offering_id=None, location_ref=None, now=None,
    ):
        """Record one auditable opening stock snapshot for a product/location scope.

        This creates an effective Inventory Position so the existing inventory
        reader sees the opening quantity. It is a snapshot, not a stock movement.
        """
        def uuid_key(value, label, optional=False):
            if value is None and optional:
                return None
            try:
                return str(UUID(str(value)))
            except (ValueError, TypeError, AttributeError) as exc:
                raise ValidationError(f"{label} must be a valid UUID") from exc

        activity_key = uuid_key(activity_id, "Activity identifier")
        product_key = uuid_key(product_id, "Product identifier")
        offering_key = uuid_key(offering_id, "Offering identifier", optional=True)
        if type(quantity_minor) is not int or quantity_minor < 0:
            raise ValidationError("Opening stock quantity must be a non-negative integer")
        if not isinstance(scope_key, str) or not scope_key.strip():
            raise ValidationError("Opening stock scope_key is required")
        scope_key = scope_key.strip()
        if location_ref is not None:
            if not isinstance(location_ref, str) or not location_ref.strip():
                raise ValidationError("Opening stock location_ref must be a non-empty string when supplied")
            location_ref = location_ref.strip()
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Opening stock actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        for value, label in ((source_ref, "source_ref"), (idempotency_key, "idempotency_key")):
            if not isinstance(value, str) or not value.strip():
                raise ValidationError(f"Opening stock {label} is required")
            if len(value.strip()) > 200:
                raise ValidationError(f"Opening stock {label} must not exceed 200 characters")
        source_ref = source_ref.strip()
        idempotency_key = idempotency_key.strip()
        recorded = self._instant(now if now is not None else datetime.now(timezone.utc), "Opening stock recorded_at")
        opened = self._instant(opening_at, "Opening stock opening_at")
        if opened > recorded:
            raise ValidationError("Opening stock opening_at cannot be in the future")
        opened_text = opened.isoformat().replace("+00:00", "Z")
        recorded_text = recorded.isoformat().replace("+00:00", "Z")

        with _atomic(connection):
            replay = connection.execute(
                "SELECT inventory_position_id, activity_id, product_id, offering_id, location_ref, "
                "scope_key, quantity_minor, source_ref, actor_context_ref, opening_at "
                "FROM inventory_opening_stock WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            requested = (
                activity_key, product_key, offering_key, location_ref, scope_key,
                quantity_minor, source_ref, actor_context_ref, opened_text,
            )
            if replay is not None:
                persisted = (
                    replay[1], replay[2], replay[3], replay[4], replay[5],
                    replay[6], replay[7], replay[8], replay[9],
                )
                if persisted != requested:
                    raise ValidationError("Opening stock idempotency_key was reused with different data")
                return self.get_position(connection, replay[0])

            duplicate = connection.execute(
                "SELECT opening_stock_id FROM inventory_opening_stock "
                "WHERE activity_id=? AND product_id=? AND COALESCE(offering_id,'')=COALESCE(?,'') "
                "AND COALESCE(location_ref,'')=COALESCE(?,'') AND scope_key=? LIMIT 1",
                (activity_key, product_key, offering_key, location_ref, scope_key),
            ).fetchone()
            if duplicate is not None:
                raise ValidationError(
                    "Opening stock already exists for this Product, Activity, location, and scope"
                )
            preexisting_position = connection.execute(
                "SELECT inventory_position_id FROM inventory_positions "
                "WHERE activity_id=? AND product_id=? "
                "AND COALESCE(offering_id,'')=COALESCE(?,'') "
                "AND COALESCE(location_ref,'')=COALESCE(?,'') AND scope_key=? LIMIT 1",
                (activity_key, product_key, offering_key, location_ref, scope_key),
            ).fetchone()
            if preexisting_position is not None:
                raise ValidationError(
                    "An Inventory Position already exists for this Product scope; "
                    "reconcile it before recording opening stock"
                )

            activity = connection.execute(
                "SELECT state FROM activities WHERE activity_id=?", (activity_key,)
            ).fetchone()
            if activity is None or activity[0] != "active":
                raise ValidationError("Opening stock requires an existing active Activity")
            product = connection.execute(
                "SELECT state FROM products WHERE product_id=?", (product_key,)
            ).fetchone()
            if product is None or product[0] != "active":
                raise ValidationError("Opening stock requires an existing active Product")
            if offering_key is not None:
                offering = connection.execute(
                    "SELECT product_id, activity_id, state, effective_from, effective_to "
                    "FROM offerings WHERE offering_id=?",
                    (offering_key,),
                ).fetchone()
                if offering is None:
                    raise ValidationError("Opening stock Offering does not exist")
                if offering[0] != product_key or offering[1] != activity_key:
                    raise ValidationError("Opening stock Product and Activity must match its Offering")
                if offering[2] != "active":
                    raise ValidationError("Opening stock requires an active Offering")
                # An active state alone is insufficient: the offering's configured
                # validity window must include the historical opening timestamp.
                for boundary, label in (
                    (offering[3], "effective_from"),
                    (offering[4], "effective_to"),
                ):
                    if boundary is not None:
                        try:
                            parsed_boundary = datetime.fromisoformat(
                                boundary.replace("Z", "+00:00")
                            )
                        except (TypeError, ValueError) as exc:
                            raise ValidationError(
                                f"Opening stock Offering {label} must be a valid timestamp"
                            ) from exc
                        if parsed_boundary.tzinfo is None or parsed_boundary.utcoffset() is None:
                            raise ValidationError(
                                f"Opening stock Offering {label} must be timezone-aware"
                            )
                        if label == "effective_from" and opened < parsed_boundary.astimezone(timezone.utc):
                            raise ValidationError(
                                "Opening stock timestamp falls before its Offering validity window"
                            )
                        if label == "effective_to" and opened >= parsed_boundary.astimezone(timezone.utc):
                            raise ValidationError(
                                "Opening stock timestamp falls outside its Offering validity window"
                            )

            position = self.create_position(
                connection, activity_id=activity_key, product_id=product_key,
                offering_id=offering_key, location_ref=location_ref, scope_key=scope_key,
                quantity_minor=quantity_minor, observed_at=opened, effective_from=opened,
                now=recorded, actor_context_ref=actor_context_ref,
            )
            effective = self.transition_position(
                connection, str(position.id), "make_effective",
                actor_context_ref=actor_context_ref, now=recorded,
            )
            connection.execute(
                "INSERT INTO inventory_opening_stock("
                "opening_stock_id, inventory_position_id, activity_id, product_id, offering_id, "
                "location_ref, scope_key, quantity_minor, source_ref, idempotency_key, "
                "actor_context_ref, opening_at, recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), str(effective.id), activity_key, product_key, offering_key,
                 location_ref, scope_key, quantity_minor, source_ref, idempotency_key,
                 actor_context_ref, opened_text, recorded_text),
            )
            return effective

    def get_position(self, connection, position_id):
        try:
            key = str(UUID(str(position_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Inventory Position identifier must be a valid UUID") from exc
        row = connection.execute(
            "SELECT inventory_position_id,activity_id,product_id,offering_id,location_ref,scope_key,"
            "quantity_minor,observed_at,effective_from,effective_to,state,version_no "
            "FROM inventory_positions WHERE inventory_position_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Inventory Position does not exist")

        history = connection.execute(
            "SELECT actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref "
            "FROM domain_history WHERE owner_domain='inventory' AND target_ref=? "
            "AND change_type='inventory_position_lifecycle' ORDER BY rowid",
            (key,),
        ).fetchall()
        version_no = row[11]
        # Preserve compatibility with pre-history/legacy rows at their initial
        # version. Once an entity advances beyond v1, missing history is a
        # persistence inconsistency and must fail closed.
        if not history and version_no == 1:
            return self._restore_position(row)
        if len(history) != version_no or not history:
            raise ValidationError("Inventory Position history does not match persisted version")
        states = []
        previous_ref = None
        action_states = {"make_effective": "effective", "close": "closed"}
        for index, (actor_ref, prior_ref, current_ref, payload_ref) in enumerate(history, start=1):
            expected_prior = None if index == 1 else history[index - 2][2]
            expected_current = f"inventory_position:{key}:v{index}"
            if (
                not isinstance(actor_ref, str) or not actor_ref.strip()
                or prior_ref != expected_prior
                or current_ref != expected_current
                or prior_ref != previous_ref
                or not isinstance(payload_ref, str)
            ):
                raise ValidationError("Inventory Position history chain is inconsistent")
            previous_ref = current_ref
            if index == 1:
                if payload_ref != "action:create;state:observed":
                    raise ValidationError("Inventory Position history has an invalid initial event")
                state_value = "observed"
            else:
                if not payload_ref.startswith("action:") or ";state:" not in payload_ref:
                    raise ValidationError("Inventory Position history lacks transition-state evidence")
                action_value, state_value = payload_ref.split(";state:", 1)
                action_value = action_value.removeprefix("action:")
                if action_states.get(action_value) != state_value:
                    raise ValidationError("Inventory Position history action and state are inconsistent")
            if state_value not in {"observed", "effective", "closed"}:
                raise ValidationError("Inventory Position history has an invalid state")
            states.append(state_value)
        if states[0] != "observed" or states[-1] != row[10]:
            raise ValidationError("Inventory Position history does not agree with persisted state")
        return self._restore_position(row)

    def transition_position(
        self, connection, position_id, action, *, actor_context_ref=None, now=None
    ):
        try:
            key = str(UUID(str(position_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Inventory Position identifier must be a valid UUID") from exc
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Inventory Position actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        timestamp = self._instant(now if now is not None else datetime.now(timezone.utc), "Inventory Position transition timestamp")
        if action not in self._TRANSITIONS:
            raise ValidationError("Unsupported Inventory Position lifecycle action")
        expected, target = self._TRANSITIONS[action]
        with _atomic(connection):
            row = connection.execute(
                "SELECT inventory_position_id,activity_id,product_id,offering_id,location_ref,scope_key,"
                "quantity_minor,observed_at,effective_from,effective_to,state,version_no "
                "FROM inventory_positions WHERE inventory_position_id=?", (key,)
            ).fetchone()
            if row is None:
                raise ValidationError("Inventory Position does not exist")
            self._validate_offering_context(connection, row[3], row[1], row[2])
            if row[10] != expected:
                raise ValidationError(f"Invalid Inventory Position transition from {row[10]} via {action}")
            cursor = connection.execute(
                "UPDATE inventory_positions SET state=?,updated_at=?,version_no=? "
                "WHERE inventory_position_id=? AND version_no=?",
                (target, timestamp.isoformat().replace("+00:00", "Z"), row[11] + 1, key, row[11]),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Inventory Position update detected")
            next_version = row[11] + 1
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "inventory", key, "inventory_position_lifecycle",
                 timestamp.isoformat().replace("+00:00", "Z"), actor_context_ref,
                 f"inventory_position:{key}:v{row[11]}",
                 f"inventory_position:{key}:v{next_version}",
                 f"action:{action};state:{target}",
                 timestamp.isoformat().replace("+00:00", "Z")),
            )
            updated = (*row[:10], target, next_version)
        return self._restore_position(updated)



@dataclass(frozen=True)
class ProductRecord:
    product: Product
    version_no: int


class ProductApplication:
    """Persist the existing Product lifecycle without implicit Commerce side effects.

    The domain model requires a non-empty name, while the original relational
    schema did not store one. Migration 0007 adds a nullable column so existing
    rows are not assigned fabricated names; legacy unnamed rows fail closed on read.
    """

    def _uuid(self, value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    def _instant(self, value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def _load_product(self, connection, product_id):
        key = str(self._uuid(product_id, "Product identifier"))
        row = connection.execute(
            "SELECT product_id, name, state, version_no FROM products WHERE product_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Product does not exist")
        persisted_id, name, state, version = row
        if not isinstance(name, str) or not name.strip():
            raise ValidationError(
                "Product name is missing; explicit legacy reconciliation is required"
            )

        history = connection.execute(
            "SELECT actor_context_ref, prior_version_ref, current_version_ref, change_payload_ref "
            "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
            "AND change_type='product_lifecycle' ORDER BY rowid",
            (key,),
        ).fetchall()
        # A version-1 row without history can be a legacy record. Once it has
        # advanced, its persisted version must be fully supported by history.
        if not history and version == 1:
            return ProductRecord(
                Product(name=name, id=UUID(persisted_id), state=ProductState(state)),
                version,
            )
        if len(history) != version or not history:
            raise ValidationError("Product history does not match persisted version")

        states = []
        previous_ref = None
        latest_recorded_name = None
        for index, (actor_ref, prior_ref, current_ref, payload_ref) in enumerate(history, start=1):
            expected_prior = None if index == 1 else history[index - 2][2]
            expected_current = f"product:{key}:v{index}"
            if (
                not isinstance(actor_ref, str) or not actor_ref.strip()
                or prior_ref != expected_prior
                or current_ref != expected_current
                or (index > 1 and prior_ref != previous_ref)
                or not isinstance(payload_ref, str)
            ):
                raise ValidationError("Product history chain is inconsistent")
            previous_ref = current_ref
            if index == 1:
                if payload_ref != "state:draft":
                    raise ValidationError("Product history has an invalid initial state")
                state_value = "draft"
            else:
                if not payload_ref.startswith("action:") or ";state:" not in payload_ref:
                    raise ValidationError("Product history lacks transition-state evidence")
                action_value, state_part = payload_ref.split(";state:", 1)
                action_value = action_value.removeprefix("action:")
                if action_value == "rename":
                    state_value, separator, encoded_name = state_part.partition(";name:")
                    if not separator or not encoded_name:
                        raise ValidationError("Product rename history lacks its recorded name")
                    try:
                        decoded_name = unquote(encoded_name, errors="strict")
                    except (UnicodeDecodeError, ValueError) as exc:
                        raise ValidationError("Product rename history has an invalid encoded name") from exc
                    if not decoded_name.strip() or quote(decoded_name, safe="") != encoded_name:
                        raise ValidationError("Product rename history has a non-canonical name")
                    latest_recorded_name = decoded_name
                else:
                    state_value = state_part
                    expected_state = {"activate": "active", "retire": "retired"}.get(action_value)
                    if expected_state is None or state_value != expected_state:
                        raise ValidationError("Product history action and state are inconsistent")
            if state_value not in {"draft", "active", "retired"}:
                raise ValidationError("Product history has an invalid state")
            if states:
                prior_state_value = states[-1]
                if action_value == "rename" and state_value != prior_state_value:
                    raise ValidationError("Product rename history changes lifecycle state")
                if action_value == "activate" and (
                    prior_state_value != "draft" or state_value != "active"
                ):
                    raise ValidationError("Product history contains an invalid activation transition")
                if action_value == "retire" and (
                    prior_state_value not in {"draft", "active"} or state_value != "retired"
                ):
                    raise ValidationError("Product history contains an invalid retirement transition")
            states.append(state_value)
        if states[0] != "draft" or states[-1] != state:
            raise ValidationError("Product history does not agree with persisted state")
        if latest_recorded_name is not None and latest_recorded_name != name:
            raise ValidationError("Product name does not agree with persisted rename history")
        return ProductRecord(
            Product(name=name, id=UUID(persisted_id), state=ProductState(state)),
            version,
        )

    def create_product(self, connection, name, *, product_id=None, actor_context_ref=None, now=None):
        product = Product(name=name, id=self._uuid(product_id, "Product identifier")
                          if product_id is not None else uuid4())
        timestamp = self._instant(now if now is not None else datetime.now(timezone.utc), "Product timestamp")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Product actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        with _atomic(connection):
            connection.execute(
                "INSERT INTO products(product_id, name, state, created_at, updated_at, version_no) "
                "VALUES(?,?,?,?,?,1)",
                (str(product.id), product.name, product.state.value, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(product.id), "product_lifecycle", timestamp,
                 actor_context_ref, None, f"product:{product.id}:v1", "state:draft", timestamp),
            )
        return ProductRecord(product, 1)

    def get_product(self, connection, product_id):
        return self._load_product(connection, product_id)

    def list_products(self, connection, *, state=None, limit=100, offset=0):
        """List persisted Products in stable order, validating each record's history."""
        allowed_states = {item.value for item in ProductState}
        if state is not None and (not isinstance(state, str) or state not in allowed_states):
            raise ValidationError("Product state filter must be draft, active, or retired")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValidationError("Product list limit must be an integer between 1 and 500")
        if type(offset) is not int or offset < 0:
            raise ValidationError("Product list offset must be a non-negative integer")
        if state is None:
            rows = connection.execute(
                "SELECT product_id FROM products ORDER BY created_at, product_id LIMIT ? OFFSET ?",
                (limit, offset),
            ).fetchall()
        else:
            rows = connection.execute(
                "SELECT product_id FROM products WHERE state=? "
                "ORDER BY created_at, product_id LIMIT ? OFFSET ?",
                (state, limit, offset),
            ).fetchall()
        # Restore through the same integrity checks used by get_product; do not
        # let list views hide legacy gaps or corrupted history.
        return tuple(self._load_product(connection, row[0]) for row in rows)


    def update_product_name(
        self, connection, product_id, name, *, expected_version, actor_context_ref, now=None
    ):
        """Rename a persisted Product atomically and record the new name in domain history."""
        # Reuse domain validation/normalization without changing the persisted lifecycle state.
        candidate = Product(name=name, id=self._uuid(product_id, "Product identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Product expected_version must be a positive integer")
        timestamp = self._instant(now if now is not None else datetime.now(timezone.utc), "Product rename timestamp")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Product actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        key = str(candidate.id)
        with _atomic(connection):
            record = self._load_product(connection, key)
            if record.version_no != expected_version:
                raise ValidationError("Product version conflict; reload before retrying")
            if candidate.name == record.product.name:
                raise ValidationError("Product name is unchanged")
            cursor = connection.execute(
                "UPDATE products SET name=?, updated_at=?, version_no=version_no+1 "
                "WHERE product_id=? AND version_no=?",
                (candidate.name, timestamp, key, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Product version conflict; reload before retrying")
            next_version = expected_version + 1
            payload = (
                f"action:rename;state:{record.product.state.value};"
                f"name:{quote(candidate.name, safe='')}"
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", key, "product_lifecycle", timestamp,
                 actor_context_ref, f"product:{key}:v{expected_version}",
                 f"product:{key}:v{next_version}", payload, timestamp),
            )
        candidate.state = record.product.state
        return ProductRecord(candidate, next_version)

    def transition_product(self, connection, product_id, action, *, expected_version, actor_context_ref, now=None):
        if not isinstance(action, str) or action not in {"activate", "retire"}:
            raise ValidationError(f"Unsupported Product action: {action}")
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Product expected_version must be a positive integer")
        timestamp = self._instant(now if now is not None else datetime.now(timezone.utc), "Product transition timestamp")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Product actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        with _atomic(connection):
            record = self._load_product(connection, product_id)
            if record.version_no == 1:
                has_history = connection.execute(
                    "SELECT 1 FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
                    "AND change_type='product_lifecycle' LIMIT 1",
                    (str(record.product.id),),
                ).fetchone()
                if has_history is None:
                    raise ValidationError(
                        "Product history is missing; explicit legacy reconciliation is required before transition"
                    )
            product = record.product
            if record.version_no != expected_version:
                raise ValidationError("Product version conflict; reload before retrying")
            if action == "activate":
                if product.state is not ProductState.DRAFT:
                    raise ValidationError(
                        f"Invalid Product transition from {product.state.value} to active"
                    )
                product.activate()
            else:
                if product.state not in {ProductState.DRAFT, ProductState.ACTIVE}:
                    raise ValidationError(
                        f"Invalid Product transition from {product.state.value} to retired"
                    )
                product.retire()
            cursor = connection.execute(
                "UPDATE products SET state=?, updated_at=?, version_no=version_no+1 "
                "WHERE product_id=? AND version_no=?",
                (product.state.value, timestamp, str(product.id), expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Product version conflict; reload before retrying")
            next_version = expected_version + 1
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(product.id), "product_lifecycle", timestamp,
                 actor_context_ref, f"product:{product.id}:v{expected_version}",
                 f"product:{product.id}:v{next_version}",
                 f"action:{action};state:{product.state.value}", timestamp),
            )
        return ProductRecord(product, expected_version + 1)


class EncounterAuthorizationError(ValidationError):
    """An Encounter operation was rejected by the authorization contract."""


class EncounterApplication:
    """Persist Encounter lifecycle operations behind an explicit authorization grant.

    The first contract version authorizes Person actors only. Grants that rely on
    Delegation fail closed because the current schema cannot represent or verify a
    complete delegation chain. No user-facing API is implied by this application service.
    """

    _DB_TO_DOMAIN = {
        "planned": "planned",
        "active": "in-progress",
        "completed": "completed",
        "cancelled": "cancelled",
    }
    _DOMAIN_TO_DB = {
        "planned": "planned",
        "in-progress": "active",
        "completed": "completed",
        "cancelled": "cancelled",
    }
    _ALLOWED = {
        "planned": ("in-progress", "cancelled"),
        "in-progress": ("completed", "cancelled"),
        "completed": (),
        "cancelled": (),
    }
    _ACTIONS = {
        "create": "health.encounter.create",
        "read": "health.encounter.read",
        "start": "health.encounter.start",
        "complete": "health.encounter.complete",
        "cancel": "health.encounter.cancel",
    }

    def _instant(self, now=None):
        instant = now or datetime.now(timezone.utc)
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError("Encounter timestamp must be timezone-aware")
        return instant.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def _uuid(self, value, label):
        try:
            return str(UUID(str(value)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    def _context_ref(self, patient_context_id):
        return f"patient_context:{patient_context_id}"

    def _audit(self, connection, *, actor_ref, action, target_ref, timestamp, status, purpose, grant_id=None):
        # Audit contains references and outcome only, never clinical content.
        operation_ref = f"purpose:{purpose};grant:{grant_id or 'none'}"
        connection.execute(
            "INSERT INTO audit_records(audit_record_id,event_type,actor_context_ref,target_ref,"
            "occurred_at,result_status,operation_ref,correlation_ref,provenance_id,created_at) "
            "VALUES(?,?,?,?,?,?,?,NULL,NULL,?)",
            (str(uuid4()), action, actor_ref, target_ref, timestamp, status, operation_ref, timestamp),
        )

    def _authorize(self, connection, *, actor_person_id, action, scope_ref, context_ref, purpose, timestamp):
        actor_id = self._uuid(actor_person_id, "Actor Person identifier")
        if not isinstance(purpose, str) or not purpose.strip():
            raise EncounterAuthorizationError("Encounter authorization requires an explicit purpose")
        purpose = purpose.strip()
        person = connection.execute(
            "SELECT state FROM persons WHERE person_id=?", (actor_id,)
        ).fetchone()
        if person is None or person[0] != "active":
            raise EncounterAuthorizationError("Encounter actor must be an existing active Person")

        rows = connection.execute(
            "SELECT authorization_grant_id,delegation_id FROM authorization_grants "
            "WHERE subject_person_id=? AND agent_id IS NULL AND action_code=? "
            "AND scope_ref=? AND context_ref=? AND purpose=? AND state='active' "
            "AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) "
            "ORDER BY authorization_grant_id",
            (actor_id, action, scope_ref, context_ref, purpose, timestamp, timestamp),
        ).fetchall()
        direct = [row for row in rows if row[1] is None]
        if direct:
            return direct[0][0]
        if rows:
            raise EncounterAuthorizationError(
                "Delegated Encounter grants are disabled until a complete delegation chain can be verified"
            )
        raise EncounterAuthorizationError("No active Authorization Grant matches this action, scope, context, and purpose")

    def _restore(self, row):
        from agent_core.domain_health import Encounter, EncounterState

        encounter_id, context_id, _encounter_type, service_id, state = row[:5]
        domain_state = self._DB_TO_DOMAIN.get(state)
        if domain_state is None:
            raise ValidationError("Encounter has an unsupported persisted state")
        return Encounter(
            patient_context_id=UUID(context_id),
            service_id=UUID(service_id) if service_id else None,
            id=UUID(encounter_id),
            state=EncounterState(domain_state),
        )

    def create_encounter(
        self, connection, patient_context_id, encounter_type, *, actor_person_id, purpose,
        service_id=None, now=None, encounter_id=None,
    ):
        """Create a planned Encounter only after an exact matching grant is verified."""
        from agent_core.domain_health import Encounter

        context_key = self._uuid(patient_context_id, "PatientContext identifier")
        if not isinstance(encounter_type, str) or not encounter_type.strip():
            raise ValidationError("Encounter type is required")
        kind = encounter_type.strip()
        service_key = self._uuid(service_id, "Service identifier") if service_id is not None else None
        timestamp = self._instant(now)
        scope_ref = self._context_ref(context_key)
        context_ref = self._context_ref(context_key)
        actor_ref = f"person:{actor_person_id}"
        denied = None
        result = None
        with _atomic(connection):
            try:
                grant_id = self._authorize(
                    connection, actor_person_id=actor_person_id, action=self._ACTIONS["create"],
                    scope_ref=scope_ref, context_ref=context_ref, purpose=purpose, timestamp=timestamp,
                )
                context = connection.execute(
                    "SELECT state FROM patient_contexts WHERE patient_context_id=?", (context_key,)
                ).fetchone()
                if context is None or context[0] != "active":
                    raise ValidationError("Encounter requires an existing active PatientContext")
                if service_key is not None:
                    service = connection.execute(
                        "SELECT state FROM services WHERE service_id=?", (service_key,)
                    ).fetchone()
                    if service is None or service[0] != "active":
                        raise ValidationError("Encounter requires an existing active Service")
                identifier = self._uuid(encounter_id, "Encounter identifier") if encounter_id is not None else str(uuid4())
                result = Encounter(
                    patient_context_id=UUID(context_key),
                    service_id=UUID(service_key) if service_key else None,
                    id=UUID(identifier),
                )
                connection.execute(
                    "INSERT INTO encounters(encounter_id,patient_context_id,encounter_type,service_id,"
                    "state,started_at,ended_at,created_at,updated_at,provenance_id,version_no) "
                    "VALUES(?,?,?,?, 'planned', ?, NULL, ?, ?, NULL, 1)",
                    (identifier, context_key, kind, service_key, timestamp, timestamp, timestamp),
                )
                self._audit(connection, actor_ref=actor_ref, action=self._ACTIONS["create"],
                            target_ref=f"encounter:{identifier}", timestamp=timestamp,
                            status="allowed", purpose=purpose.strip(), grant_id=grant_id)
            except (EncounterAuthorizationError, ValidationError) as exc:
                denied = exc
                self._audit(connection, actor_ref=actor_ref, action=self._ACTIONS["create"],
                            target_ref=scope_ref, timestamp=timestamp, status="denied",
                            purpose=purpose.strip() if isinstance(purpose, str) and purpose.strip() else "unspecified")
        if denied is not None:
            raise denied
        return result

    def get_encounter(self, connection, encounter_id, *, actor_person_id, purpose, now=None):
        """Read a persisted Encounter only after an exact matching read grant."""
        key = self._uuid(encounter_id, "Encounter identifier")
        timestamp = self._instant(now)
        actor_ref = f"person:{actor_person_id}"
        denied = None
        result = None
        with _atomic(connection):
            row = connection.execute(
                "SELECT encounter_id,patient_context_id,encounter_type,service_id,state,version_no "
                "FROM encounters WHERE encounter_id=?", (key,)
            ).fetchone()
            if row is None:
                denied = ValidationError("Encounter does not exist")
                self._audit(connection, actor_ref=actor_ref, action=self._ACTIONS["read"],
                            target_ref=f"encounter:{key}", timestamp=timestamp, status="denied",
                            purpose=purpose.strip() if isinstance(purpose, str) and purpose.strip() else "unspecified")
            else:
                context_ref = self._context_ref(row[1])
                try:
                    grant_id = self._authorize(
                        connection, actor_person_id=actor_person_id, action=self._ACTIONS["read"],
                        scope_ref=f"encounter:{key}", context_ref=context_ref,
                        purpose=purpose, timestamp=timestamp,
                    )
                    result = self._restore(row)
                    self._audit(connection, actor_ref=actor_ref, action=self._ACTIONS["read"],
                                target_ref=f"encounter:{key}", timestamp=timestamp,
                                status="allowed", purpose=purpose.strip(), grant_id=grant_id)
                except (EncounterAuthorizationError, ValidationError) as exc:
                    denied = exc
                    self._audit(connection, actor_ref=actor_ref, action=self._ACTIONS["read"],
                                target_ref=f"encounter:{key}", timestamp=timestamp, status="denied",
                                purpose=purpose.strip() if isinstance(purpose, str) and purpose.strip() else "unspecified")
        if denied is not None:
            raise denied
        return result

    def transition_encounter(
        self, connection, encounter_id, action, *, actor_person_id, purpose, now=None,
    ):
        """Apply a lifecycle transition only with the action-specific authorization grant."""
        from agent_core.domain_health import EncounterState

        key = self._uuid(encounter_id, "Encounter identifier")
        timestamp = self._instant(now)
        if action not in ("start", "complete", "cancel"):
            raise ValidationError("Unsupported Encounter lifecycle action")
        action_code = self._ACTIONS[action]
        actor_ref = f"person:{actor_person_id}"
        denied = None
        result = None
        with _atomic(connection):
            row = connection.execute(
                "SELECT encounter_id,patient_context_id,encounter_type,service_id,state,version_no "
                "FROM encounters WHERE encounter_id=?", (key,)
            ).fetchone()
            if row is None:
                denied = ValidationError("Encounter does not exist")
                self._audit(connection, actor_ref=actor_ref, action=action_code,
                            target_ref=f"encounter:{key}", timestamp=timestamp, status="denied",
                            purpose=purpose.strip() if isinstance(purpose, str) and purpose.strip() else "unspecified")
            else:
                try:
                    grant_id = self._authorize(
                        connection, actor_person_id=actor_person_id, action=action_code,
                        scope_ref=f"encounter:{key}", context_ref=self._context_ref(row[1]),
                        purpose=purpose, timestamp=timestamp,
                    )
                    current = self._DB_TO_DOMAIN.get(row[4])
                    if current is None:
                        raise ValidationError("Encounter has an unsupported persisted state")
                    target = {"start": "in-progress", "complete": "completed", "cancel": "cancelled"}[action]
                    if target not in self._ALLOWED[current]:
                        raise ValidationError(f"Invalid Encounter transition from {current} via {action}")
                    next_version = row[5] + 1
                    ended_at = timestamp if target in ("completed", "cancelled") else None
                    cursor = connection.execute(
                        "UPDATE encounters SET state=?, updated_at=?, ended_at=?, version_no=? "
                        "WHERE encounter_id=? AND version_no=?",
                        (self._DOMAIN_TO_DB[target], timestamp, ended_at, next_version, key, row[5]),
                    )
                    if cursor.rowcount != 1:
                        raise ValidationError("Concurrent Encounter update detected")
                    updated = (row[0], row[1], row[2], row[3], self._DOMAIN_TO_DB[target], next_version)
                    result = self._restore(updated)
                    result.state = EncounterState(target)
                    self._audit(connection, actor_ref=actor_ref, action=action_code,
                                target_ref=f"encounter:{key}", timestamp=timestamp,
                                status="allowed", purpose=purpose.strip(), grant_id=grant_id)
                except (EncounterAuthorizationError, ValidationError) as exc:
                    denied = exc
                    self._audit(connection, actor_ref=actor_ref, action=action_code,
                                target_ref=f"encounter:{key}", timestamp=timestamp, status="denied",
                                purpose=purpose.strip() if isinstance(purpose, str) and purpose.strip() else "unspecified")
        if denied is not None:
            raise denied
        return result

