from contextlib import contextmanager
"""Persisted Commerce Offering lifecycle using the existing domain and schema."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_inventory import Offering, OfferingState
from agent_core.shared import ValidationError


@dataclass(frozen=True)
class OfferingRecord:
    offering: Offering
    version_no: int


class OfferingApplication:
    """Create, restore, and transition Offerings without inventing Availability.

    The persisted schema supports draft/active/ended/withdrawn. This service
    deliberately does not calculate inventory, availability, proximity, or rank.
    """

    _ACTIONS = {
        "activate": (OfferingState.ACTIVE, (OfferingState.DRAFT,)),
        "end": (OfferingState.ENDED, (OfferingState.ACTIVE,)),
        "withdraw": (OfferingState.WITHDRAWN, (OfferingState.DRAFT, OfferingState.ACTIVE)),
    }


    @staticmethod
    @contextmanager
    def _atomic(connection):
        """Own a transaction only when the caller has not already opened one."""
        if connection.in_transaction:
            savepoint = "operation_" + uuid4().hex
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
    def _instant(value, label, *, optional=False):
        if value is None and optional:
            return None
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _domain_instant(value, label, *, optional=False):
        if value is None and optional:
            return None
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except (ValueError, AttributeError) as exc:
            raise ValidationError(f"Persisted Offering {label} is invalid") from exc
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ValidationError(f"Persisted Offering {label} must be timezone-aware")
        return parsed

    def _load(self, connection, offering_id):
        key = str(self._uuid(offering_id, "Offering identifier"))
        row = connection.execute(
            "SELECT offering_id, product_id, activity_id, service_id, state, "
            "effective_from, effective_to, version_no FROM offerings WHERE offering_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Offering does not exist")
        oid, product_id, activity_id, service_id, state, starts, ends, version = row
        offering = Offering(
            product_id=UUID(product_id),
            activity_id=UUID(activity_id),
            service_id=UUID(service_id) if service_id is not None else None,
            id=UUID(oid),
            state=OfferingState(state),
            effective_from=self._domain_instant(starts, "effective_from"),
            effective_to=self._domain_instant(ends, "effective_to", optional=True),
        )

        history = connection.execute(
            "SELECT actor_context_ref, prior_version_ref, current_version_ref, change_payload_ref "
            "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
            "AND change_type='offering_lifecycle' ORDER BY rowid",
            (key,),
        ).fetchall()
        # Older version-1 Offerings may predate durable lifecycle history. Keep
        # them readable, but never extend an unaudited chain.
        if not history and version == 1:
            return OfferingRecord(offering, version)
        if not history or len(history) != version:
            raise ValidationError("Offering history does not match persisted version")

        states = []
        previous_ref = None
        action_states = {"activate": "active", "end": "ended", "withdraw": "withdrawn"}
        for index, (actor_ref, prior_ref, current_ref, payload_ref) in enumerate(history, start=1):
            expected_prior = None if index == 1 else history[index - 2][2]
            expected_current = f"offering:{key}:v{index}"
            if (
                not isinstance(actor_ref, str) or not actor_ref.strip()
                or prior_ref != expected_prior
                or current_ref != expected_current
                or (index > 1 and prior_ref != previous_ref)
                or not isinstance(payload_ref, str)
            ):
                raise ValidationError("Offering history chain is inconsistent")
            previous_ref = current_ref
            if index == 1:
                if payload_ref != "state:draft":
                    raise ValidationError("Offering history has an invalid initial state")
                state_value = "draft"
            else:
                if not payload_ref.startswith("action:") or ";state:" not in payload_ref:
                    raise ValidationError("Offering history lacks transition-state evidence")
                action_value, state_value = payload_ref.split(";state:", 1)
                action_value = action_value.removeprefix("action:")
                if action_states.get(action_value) != state_value:
                    raise ValidationError("Offering history action and state are inconsistent")
            if state_value not in {"draft", "active", "ended", "withdrawn"}:
                raise ValidationError("Offering history has an invalid state")
            states.append(state_value)
        if states[0] != "draft" or states[-1] != state:
            raise ValidationError("Offering history does not agree with persisted state")
        return OfferingRecord(offering, version)

    def create_offering(
        self, connection, *, product_id, activity_id, effective_from,
        service_id=None, effective_to=None, offering_id=None, actor_context_ref=None, now=None,
    ) -> OfferingRecord:
        product_key = str(self._uuid(product_id, "Product identifier"))
        activity_key = str(self._uuid(activity_id, "Activity identifier"))
        service_key = str(self._uuid(service_id, "Service identifier")) if service_id is not None else None
        starts = self._instant(effective_from, "Offering.effective_from")
        ends = self._instant(effective_to, "Offering.effective_to", optional=True)
        created = self._instant(now if now is not None else datetime.now(timezone.utc), "Offering creation timestamp")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Offering actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        key = str(self._uuid(offering_id, "Offering identifier")) if offering_id is not None else None

        # Reuse domain validation before writing anything.
        offering = Offering(
            product_id=UUID(product_key), activity_id=UUID(activity_key),
            service_id=UUID(service_key) if service_key is not None else None,
            id=UUID(key) if key is not None else uuid4(),
            state=OfferingState.DRAFT,
            effective_from=self._domain_instant(starts, "effective_from"),
            effective_to=self._domain_instant(ends, "effective_to", optional=True),
        )
        with self._atomic(connection):
            for table, column, ref, label in (
                ("products", "product_id", product_key, "Product"),
                ("activities", "activity_id", activity_key, "Activity"),
            ):
                if connection.execute(
                    f"SELECT 1 FROM {table} WHERE {column}=?", (ref,)
                ).fetchone() is None:
                    raise ValidationError(f"Offering requires an existing {label}")
            if service_key is not None and connection.execute(
                "SELECT 1 FROM services WHERE service_id=?", (service_key,)
            ).fetchone() is None:
                raise ValidationError("Offering requires an existing Service when service_id is supplied")
            connection.execute(
                "INSERT INTO offerings(offering_id, product_id, service_id, activity_id, state, "
                "effective_from, effective_to, created_at, updated_at, version_no) "
                "VALUES(?,?,?,?,?,?,?,?,?,1)",
                (str(offering.id), product_key, service_key, activity_key, offering.state.value,
                 starts, ends, created, created),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(offering.id), "offering_lifecycle", created,
                 actor_context_ref, None, f"offering:{offering.id}:v1", "state:draft", created),
            )
        return OfferingRecord(offering, 1)

    def get_offering(self, connection, offering_id) -> OfferingRecord:
        return self._load(connection, offering_id)

    def transition_offering(
        self, connection, offering_id, action, *, expected_version, actor_context_ref, now=None,
    ) -> OfferingRecord:
        if not isinstance(action, str) or action not in self._ACTIONS:
            raise ValidationError(f"Unsupported Offering action: {action}")
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Offering expected_version must be a positive integer")
        instant = self._instant(now if now is not None else datetime.now(timezone.utc), "Offering transition timestamp")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Offering actor_context_ref is required")
        actor_context_ref = actor_context_ref.strip()
        with self._atomic(connection):
            record = self._load(connection, offering_id)
            if record.version_no == 1:
                has_history = connection.execute(
                    "SELECT 1 FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
                    "AND change_type='offering_lifecycle' LIMIT 1",
                    (str(record.offering.id),),
                ).fetchone()
                if has_history is None:
                    raise ValidationError(
                        "Offering history is missing; explicit legacy reconciliation is required before transition"
                    )
            offering = record.offering
            target, allowed = self._ACTIONS[action]
            if offering.state not in allowed:
                raise ValidationError(
                    f"Invalid Offering transition from {offering.state.value} to {target.value}"
                )
            if record.version_no != expected_version:
                raise ValidationError("Offering version conflict; reload before retrying")
            if action == "activate":
                offering.activate()
            elif action == "end":
                offering.end()
            else:
                offering.withdraw()
            cursor = connection.execute(
                "UPDATE offerings SET state=?, updated_at=?, version_no=version_no+1 "
                "WHERE offering_id=? AND version_no=?",
                (offering.state.value, instant, str(offering.id), expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Offering version conflict; reload before retrying")
            next_version = expected_version + 1
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", str(offering.id), "offering_lifecycle", instant,
                 actor_context_ref, f"offering:{offering.id}:v{expected_version}",
                 f"offering:{offering.id}:v{next_version}",
                 f"action:{action};state:{offering.state.value}", instant),
            )
        return OfferingRecord(offering, expected_version + 1)
