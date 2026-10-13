"""Persisted procurement lifecycle; receipts do not change inventory or finance."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_purchases import Purchase, PurchaseLine, PurchaseState, ReceiptEvent
from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PurchaseRecord:
    purchase: Purchase
    version_no: int


class PurchaseApplication:
    _ACTIONS = {
        "submit": ("submit_purchase", PurchaseState.SUBMITTED),
        "approve": ("approve_purchase", PurchaseState.APPROVED),
        "order": ("order_purchase", PurchaseState.ORDERED),
        "cancel": ("cancel_purchase", PurchaseState.CANCELLED),
    }

    @staticmethod
    @contextmanager
    def _atomic(connection):
        if connection.in_transaction:
            savepoint = "purchase_" + uuid4().hex
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

    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value, label):
        if value is None:
            value = datetime.now(timezone.utc)
        elif isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _grant_instant(value, label):
        if not isinstance(value, str):
            raise ValidationError(f"AuthorizationGrant {label} must be a timestamp")
        try:
            instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError(f"AuthorizationGrant {label} must be a timestamp") from exc
        if instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError(f"AuthorizationGrant {label} must be timezone-aware")
        return instant.astimezone(timezone.utc)

    def _authorize(self, connection, grant_id, actor_id, action, scope_ref, activity_id, timestamp):
        grant_key = str(self._uuid(grant_id, "AuthorizationGrant identifier"))
        row = connection.execute(
            "SELECT subject_person_id, agent_id, action_code, scope_ref, context_ref, "
            "delegation_id, state, effective_from, effective_to FROM authorization_grants "
            "WHERE authorization_grant_id=?", (grant_key,),
        ).fetchone()
        if row is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        subject, agent_id, action_code, grant_scope, context_ref, delegation_id, state, starts, ends = row
        if state != "active" or subject != str(actor_id) or agent_id is not None:
            raise ValidationError("AuthorizationGrant must be active and match the Person actor")
        person = connection.execute("SELECT state FROM persons WHERE person_id=?", (str(actor_id),)).fetchone()
        if person is None or person[0] != "active":
            raise ValidationError("Purchase action requires an active persisted Person")
        if action_code != action:
            raise ValidationError(f"AuthorizationGrant does not permit action {action}")
        if grant_scope != str(scope_ref) or context_ref != str(activity_id):
            raise ValidationError("AuthorizationGrant scope or Activity context does not match")
        instant = self._grant_instant(timestamp, "operation timestamp")
        if self._grant_instant(starts, "effective_from") > instant or (
            ends is not None and instant >= self._grant_instant(ends, "effective_to")
        ):
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if delegation_id is not None:
            delegation = connection.execute(
                "SELECT subject_person_id, context_ref, state, effective_from, effective_to "
                "FROM delegations WHERE delegation_id=?", (delegation_id,),
            ).fetchone()
            if delegation is None:
                raise ValidationError("AuthorizationGrant delegation does not exist")
            subject_ref, context_ref, delegation_state, starts, ends = delegation
            if subject_ref != str(actor_id) or context_ref != str(activity_id) or delegation_state != "active":
                raise ValidationError("Delegation does not match actor and Activity")
            if self._grant_instant(starts, "delegation effective_from") > instant or (
                ends is not None and instant >= self._grant_instant(ends, "delegation effective_to")
            ):
                raise ValidationError("Delegation is outside its effective period")

    def _load(self, connection, purchase_id):
        key = str(self._uuid(purchase_id, "Purchase identifier"))
        row = connection.execute(
            "SELECT purchase_id, supplier_ref, activity_id, state, version_no "
            "FROM purchases WHERE purchase_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Purchase does not exist")
        persisted_id, supplier_ref, activity_id, state, version = row
        line_rows = connection.execute(
            "SELECT purchase_line_id, product_id, quantity_units, unit_label, unit_cost_minor, "
            "currency_code, received_quantity_units FROM purchase_lines "
            "WHERE purchase_id=? ORDER BY rowid", (key,),
        ).fetchall()
        lines = tuple(PurchaseLine(
            id=UUID(line_id), product_id=UUID(product_id), quantity_units=quantity,
            unit_label=unit_label, unit_cost_minor=cost, currency_code=currency,
            received_quantity_units=received,
        ) for line_id, product_id, quantity, unit_label, cost, currency, received in line_rows)
        receipt_rows = connection.execute(
            "SELECT receipt_id, purchase_line_id, quantity_units FROM purchase_receipts "
            "WHERE purchase_id=? ORDER BY rowid", (key,),
        ).fetchall()
        receipts = tuple(ReceiptEvent(
            id=UUID(receipt_id), line_id=UUID(line_id), quantity_units=quantity,
        ) for receipt_id, line_id, quantity in receipt_rows)
        history_rows = connection.execute(
            "SELECT actor_context_ref, prior_version_ref, current_version_ref, change_payload_ref "
            "FROM domain_history WHERE owner_domain='procurement' AND target_ref=? "
            "AND change_type='purchase_state_transition' ORDER BY rowid", (key,),
        ).fetchall()
        if len(history_rows) != version or not history_rows:
            raise ValidationError("Purchase history does not match persisted version")
        history = []
        for index, (actor, prior, current, payload) in enumerate(history_rows, start=1):
            if not isinstance(actor, str) or not actor.strip():
                raise ValidationError("Purchase history actor is missing")
            if prior != (None if index == 1 else history_rows[index - 2][2]):
                raise ValidationError("Purchase history prior-version chain is inconsistent")
            if current != f"purchase:{key}:v{index}":
                raise ValidationError("Purchase history current-version chain is inconsistent")
            if not isinstance(payload, str) or not payload.startswith("state:"):
                raise ValidationError("Purchase history state evidence is missing")
            try:
                state_value = payload.removeprefix("state:").split(";", 1)[0]
                history.append(PurchaseState(state_value))
            except ValueError as exc:
                raise ValidationError("Purchase history contains an unknown state") from exc
        if history[0] is not PurchaseState.DRAFT or history[-1].value != state:
            raise ValidationError("Purchase history does not match current state")
        purchase = Purchase(
            id=UUID(persisted_id), supplier_ref=supplier_ref, activity_id=UUID(activity_id),
            lines=lines, state=PurchaseState(state), history=tuple(history), receipts=receipts,
        )
        return PurchaseRecord(purchase, version)

    def create_purchase(
        self, connection, supplier_ref, activity_id, lines, actor_context_ref,
        authorization_grant_id, *, purchase_id=None, now=None,
    ):
        timestamp = self._timestamp(now, "Purchase timestamp")
        actor = self._uuid(actor_context_ref, "Actor identifier")
        activity = self._uuid(activity_id, "Activity identifier")
        identifier = self._uuid(purchase_id, "Purchase identifier") if purchase_id is not None else uuid4()
        if not isinstance(lines, (tuple, list)) or not lines or any(not isinstance(x, PurchaseLine) for x in lines):
            raise ValidationError("Purchase requires one or more PurchaseLine values")
        if any(line.received_quantity_units != 0 for line in lines):
            raise ValidationError("New Purchase lines cannot already contain received quantities")
        purchase = Purchase(supplier_ref=supplier_ref, activity_id=activity, lines=tuple(lines), id=identifier)
        with self._atomic(connection):
            self._authorize(connection, authorization_grant_id, actor, "create_purchase",
                            activity, activity, timestamp)
            activity_row = connection.execute("SELECT state FROM activities WHERE activity_id=?",
                                              (str(activity),)).fetchone()
            if activity_row is None or activity_row[0] != "active":
                raise ValidationError("Purchase requires an existing active Activity")
            for line in purchase.lines:
                product = connection.execute("SELECT state FROM products WHERE product_id=?",
                                              (str(line.product_id),)).fetchone()
                if product is None or product[0] != "active":
                    raise ValidationError("Purchase lines require existing active Products")
            connection.execute(
                "INSERT INTO purchases(purchase_id,supplier_ref,activity_id,state,created_at,updated_at,created_by_ref,version_no) "
                "VALUES(?,?,?,?,?,?,?,1)",
                (str(identifier), purchase.supplier_ref, str(activity), purchase.state.value,
                 timestamp, timestamp, str(actor)),
            )
            for line in purchase.lines:
                connection.execute(
                    "INSERT INTO purchase_lines(purchase_line_id,purchase_id,product_id,quantity_units,unit_label,"
                    "unit_cost_minor,currency_code,received_quantity_units,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    (str(line.id), str(identifier), str(line.product_id), line.quantity_units, line.unit_label,
                     line.unit_cost_minor, line.currency_code, 0, timestamp),
                )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "procurement", str(identifier), "purchase_state_transition", timestamp,
                 str(actor), None, f"purchase:{identifier}:v1", "state:draft", timestamp),
            )
        return PurchaseRecord(purchase, 1)

    def transition_purchase(self, connection, purchase_id, actor_context_ref, authorization_grant_id, action, *, now=None):
        if action not in self._ACTIONS:
            raise ValidationError("Unsupported Purchase lifecycle action")
        timestamp = self._timestamp(now, "Purchase transition timestamp")
        actor = self._uuid(actor_context_ref, "Actor identifier")
        key = self._uuid(purchase_id, "Purchase identifier")
        with self._atomic(connection):
            record = self._load(connection, key)
            grant_action, target = self._ACTIONS[action]
            self._authorize(connection, authorization_grant_id, actor, grant_action,
                            key, record.purchase.activity_id, timestamp)
            transition = {
                "submit": record.purchase.submit, "approve": record.purchase.approve,
                "order": record.purchase.mark_ordered, "cancel": record.purchase.cancel,
            }[action]
            updated = transition()
            if updated.state is target and updated is record.purchase:
                return record
            self._persist_state(connection, record, updated, actor, timestamp)
        return PurchaseRecord(updated, record.version_no + 1)

    def _persist_state(self, connection, record, updated, actor, timestamp):
        purchase = record.purchase
        next_version = record.version_no + 1
        cursor = connection.execute(
            "UPDATE purchases SET state=?,updated_at=?,version_no=? WHERE purchase_id=? AND version_no=?",
            (updated.state.value, timestamp, next_version, str(purchase.id), record.version_no),
        )
        if cursor.rowcount != 1:
            raise ValidationError("Concurrent Purchase update detected; reload before retrying")
        connection.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()), "procurement", str(purchase.id), "purchase_state_transition", timestamp,
             str(actor), f"purchase:{purchase.id}:v{record.version_no}",
             f"purchase:{purchase.id}:v{next_version}", f"state:{updated.state.value}", timestamp),
        )

    def receive_purchase(self, connection, purchase_id, line_id, quantity_units,
                         actor_context_ref, authorization_grant_id, *, now=None):
        timestamp = self._timestamp(now, "Purchase receipt timestamp")
        actor = self._uuid(actor_context_ref, "Actor identifier")
        key = self._uuid(purchase_id, "Purchase identifier")
        line_key = self._uuid(line_id, "Purchase line identifier")
        with self._atomic(connection):
            record = self._load(connection, key)
            self._authorize(connection, authorization_grant_id, actor, "receive_purchase",
                            key, record.purchase.activity_id, timestamp)
            updated = record.purchase.receive(line_key, quantity_units)
            next_version = record.version_no + 1
            cursor = connection.execute(
                "UPDATE purchases SET state=?,updated_at=?,version_no=? WHERE purchase_id=? AND version_no=?",
                (updated.state.value, timestamp, next_version, str(key), record.version_no),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Purchase receipt detected; reload before retrying")
            line = next(x for x in updated.lines if x.id == line_key)
            connection.execute(
                "UPDATE purchase_lines SET received_quantity_units=? WHERE purchase_line_id=? AND purchase_id=?",
                (line.received_quantity_units, str(line_key), str(key)),
            )
            event = updated.receipts[-1]
            connection.execute(
                "INSERT INTO purchase_receipts(receipt_id,purchase_id,purchase_line_id,quantity_units,received_at,actor_context_ref) "
                "VALUES(?,?,?,?,?,?)",
                (str(event.id), str(key), str(line_key), quantity_units, timestamp, str(actor)),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "procurement", str(key), "purchase_state_transition", timestamp,
                 str(actor), f"purchase:{key}:v{record.version_no}",
                 f"purchase:{key}:v{next_version}", f"state:{updated.state.value};receipt:{event.id}", timestamp),
            )
        return PurchaseRecord(updated, next_version)

    def get_purchase(self, connection, purchase_id):
        return self._load(connection, purchase_id)
