"""Internal persistence for the SPEC-0016 Instrument lifecycle.

This is an internal domain persistence primitive, not a user-facing endpoint:
SPEC-0016 does not enumerate Instrument-specific authorization action codes.
It does not apply financial effects or mutate Sale, Invoice, Payment, Settlement,
Financial Account, or Ledger records.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class InstrumentRecord:
    instrument_id: UUID
    instrument_type: str
    issuer_ref: str
    canonical_identifier: str
    state: str
    created_at: str
    updated_at: str
    version_no: int


class InstrumentApplication:
    """Persist Instrument state and usage history without inventing value rules."""

    _TRANSITIONS = {
        "activate": (("issued",), "active"),
        "redeem": (("active",), "redeemed"),
        "expire": (("issued", "active"), "expired"),
        "revoke": (("issued", "active"), "revoked"),
    }

    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def _load(self, connection, instrument_id):
        key = str(self._uuid(instrument_id, "Instrument identifier"))
        row = connection.execute(
            "SELECT instrument_id,instrument_type,issuer_ref,canonical_identifier,state,"
            "created_at,updated_at,version_no FROM instruments WHERE instrument_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Instrument does not exist")
        instrument, kind, issuer, canonical, state, created, updated, version = row
        return InstrumentRecord(UUID(instrument), kind, issuer, canonical, state, created, updated, version)

    def create_instrument(
        self, connection, *, instrument_id, instrument_type, issuer_ref,
        canonical_identifier, actor_context_ref, now=None, connected=True,
    ):
        if connected is not True:
            raise ValidationError("Instrument writes require connectivity")
        key = str(self._uuid(instrument_id, "Instrument identifier"))
        if not isinstance(instrument_type, str) or not instrument_type.strip():
            raise ValidationError("Instrument instrument_type is required")
        if not isinstance(issuer_ref, str) or not issuer_ref.strip():
            raise ValidationError("Instrument issuer_ref is required")
        if not isinstance(canonical_identifier, str) or not canonical_identifier.strip():
            raise ValidationError("Instrument canonical_identifier is required")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Instrument actor_context_ref is required")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Instrument creation timestamp")
        kind, issuer, canonical = instrument_type.strip(), issuer_ref.strip(), canonical_identifier.strip()
        with connection:
            connection.execute(
                "INSERT INTO instruments(instrument_id,instrument_type,issuer_ref,canonical_identifier,"
                "state,created_at,updated_at,version_no) VALUES(?,?,?,?, 'issued',?,?,1)",
                (key, kind, issuer, canonical, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "instrument", key, "instrument_lifecycle", timestamp,
                 actor_context_ref.strip(), None, f"instrument:{key}:v1", "state:issued", timestamp),
            )
        return self._load(connection, key)

    def get_instrument(self, connection, instrument_id):
        return self._load(connection, instrument_id)

    def transition_instrument(
        self, connection, instrument_id, action, *, expected_version,
        actor_context_ref, now=None, action_ref=None, context_ref=None, connected=True,
    ):
        if connected is not True:
            raise ValidationError("Instrument writes require connectivity")
        key = str(self._uuid(instrument_id, "Instrument identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Instrument expected_version must be a positive integer")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Instrument actor_context_ref is required")
        if action not in self._TRANSITIONS:
            raise ValidationError("Unsupported Instrument transition")
        allowed, next_state = self._TRANSITIONS[action]
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Instrument transition timestamp")
        if action == "redeem":
            if not isinstance(action_ref, str) or not action_ref.strip():
                raise ValidationError("Instrument redemption requires an action_ref")
            if context_ref is not None and (not isinstance(context_ref, str) or not context_ref.strip()):
                raise ValidationError("Instrument context_ref must be non-empty when supplied")
        with connection:
            current = self._load(connection, key)
            if current.version_no != expected_version:
                raise ValidationError("Instrument version conflict; reload before retrying")
            if current.state not in allowed:
                raise ValidationError(f"Invalid Instrument transition from {current.state} via {action}")
            next_version = current.version_no + 1
            result = connection.execute(
                "UPDATE instruments SET state=?,updated_at=?,version_no=? "
                "WHERE instrument_id=? AND state=? AND version_no=?",
                (next_state, timestamp, next_version, key, current.state, expected_version),
            )
            if result.rowcount != 1:
                raise ValidationError("Concurrent Instrument update detected")
            if action == "redeem":
                connection.execute(
                    "INSERT INTO instrument_usages(instrument_usage_id,instrument_id,action_ref,context_ref,"
                    "state,occurred_at,created_at,updated_at) VALUES(?,?,?,?, 'recorded',?,?,?)",
                    (str(uuid4()), key, action_ref.strip(), context_ref.strip() if context_ref else None,
                     timestamp, timestamp, timestamp),
                )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "instrument", key, "instrument_lifecycle", timestamp,
                 actor_context_ref.strip(), f"instrument:{key}:v{current.version_no}",
                 f"instrument:{key}:v{next_version}", f"action:{action};state:{next_state}", timestamp),
            )
        return self._load(connection, key)
