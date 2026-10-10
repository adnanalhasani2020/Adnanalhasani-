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
        return OfferingRecord(offering, version)

    def create_offering(
        self, connection, *, product_id, activity_id, effective_from,
        service_id=None, effective_to=None, offering_id=None, now=None,
    ) -> OfferingRecord:
        product_key = str(self._uuid(product_id, "Product identifier"))
        activity_key = str(self._uuid(activity_id, "Activity identifier"))
        service_key = str(self._uuid(service_id, "Service identifier")) if service_id is not None else None
        starts = self._instant(effective_from, "Offering.effective_from")
        ends = self._instant(effective_to, "Offering.effective_to", optional=True)
        created = self._instant(now or datetime.now(timezone.utc), "Offering creation timestamp")
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
        with connection:
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
        return OfferingRecord(offering, 1)

    def get_offering(self, connection, offering_id) -> OfferingRecord:
        return self._load(connection, offering_id)

    def transition_offering(
        self, connection, offering_id, action, *, expected_version, now=None,
    ) -> OfferingRecord:
        if action not in self._ACTIONS:
            raise ValidationError(f"Unsupported Offering action: {action}")
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Offering expected_version must be a positive integer")
        instant = self._instant(now or datetime.now(timezone.utc), "Offering transition timestamp")
        with connection:
            record = self._load(connection, offering_id)
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
        return OfferingRecord(offering, expected_version + 1)
