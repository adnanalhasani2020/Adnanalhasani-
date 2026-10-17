"""Persisted administrator-controlled Finance policy settings."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from uuid import UUID, uuid4

from agent_core.financial_currency_policy import DEFAULT_CURRENCY, normalize_supported_currency
from agent_core.shared import ValidationError

@dataclass(frozen=True)
class FinancialPolicySettings:
    activity_id: UUID
    enabled_currencies: tuple[str, ...] = ("SAR", "YER", "USD")
    default_currency: str = DEFAULT_CURRENCY
    display_decimal_places: int = 2
    settlement_cardinality: str = "single"
    posted_voucher_policy: str = "reversal_only"
    require_full_cycle_validation: bool = True
    version_no: int = 0
    updated_at: str | None = None
    updated_by_person_id: UUID | None = None

class FinancialPolicySettingsApplication:
    ACTION_CODE = "financial_policy.settings.update"

    @staticmethod
    @contextmanager
    def _atomic(connection):
        if connection.in_transaction:
            name = "finance_policy_" + uuid4().hex
            connection.execute(f"SAVEPOINT {name}")
            try:
                yield
            except BaseException:
                connection.execute(f"ROLLBACK TO SAVEPOINT {name}")
                connection.execute(f"RELEASE SAVEPOINT {name}")
                raise
            else:
                connection.execute(f"RELEASE SAVEPOINT {name}")
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
    def _instant(value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc)

    @classmethod
    def _timestamp(cls, value, label):
        return cls._instant(value, label).isoformat().replace("+00:00", "Z")

    @staticmethod
    def validate_policy(*, enabled_currencies=("SAR", "YER", "USD"), default_currency=DEFAULT_CURRENCY,
                        display_decimal_places=2, settlement_cardinality="single",
                        posted_voucher_policy="reversal_only", require_full_cycle_validation=True):
        if isinstance(enabled_currencies, (str, bytes)):
            raise ValidationError("enabled_currencies must be a collection of currency codes")
        try:
            requested = {normalize_supported_currency(c) for c in enabled_currencies}
            enabled = tuple(code for code in ("SAR", "YER", "USD") if code in requested)
        except TypeError as exc:
            raise ValidationError("enabled_currencies must be an iterable of currency codes") from exc
        if not enabled:
            raise ValidationError("At least one currency must remain enabled")
        default = normalize_supported_currency(default_currency)
        if default not in enabled:
            raise ValidationError("default_currency must be enabled")
        if type(display_decimal_places) is not int or not 0 <= display_decimal_places <= 4:
            raise ValidationError("display_decimal_places must be an integer from 0 to 4")
        if settlement_cardinality not in {"single", "multiple"}:
            raise ValidationError("settlement_cardinality must be 'single' or 'multiple'")
        if posted_voucher_policy not in {"reversal_only", "disallow"}:
            raise ValidationError("posted_voucher_policy must be 'reversal_only' or 'disallow'")
        if type(require_full_cycle_validation) is not bool:
            raise ValidationError("require_full_cycle_validation must be a boolean")
        return dict(enabled_currencies=enabled, default_currency=default,
                    display_decimal_places=display_decimal_places,
                    settlement_cardinality=settlement_cardinality,
                    posted_voucher_policy=posted_voucher_policy,
                    require_full_cycle_validation=require_full_cycle_validation)

    def _authorize(self, db, activity, grant_id, action_id, now):
        grant_key = str(self._uuid(grant_id, "AuthorizationGrant identifier"))
        action_key = str(self._uuid(action_id, "AgentAction identifier"))
        row = db.execute("SELECT agent_id,action_code,scope_ref,context_ref,state,effective_from,effective_to "
                         "FROM authorization_grants WHERE authorization_grant_id=?", (grant_key,)).fetchone()
        if row is None:
            raise ValidationError("A persisted AuthorizationGrant is required")
        agent, code, scope, context, state, starts, ends = row
        if state != "active" or agent is None or code != self.ACTION_CODE:
            raise ValidationError("An active agent-bound Finance policy AuthorizationGrant is required")
        if scope != activity or context != activity:
            raise ValidationError("AuthorizationGrant scope/context must match Finance policy Activity")
        instant = self._instant(now, "Finance policy operation timestamp")
        if self._instant(starts, "AuthorizationGrant effective_from") > instant:
            raise ValidationError("AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= self._instant(ends, "AuthorizationGrant effective_to"):
            raise ValidationError("AuthorizationGrant is outside its effective period")
        action = db.execute("SELECT agent_id,action_code,target_ref,context_ref,authorization_grant_id,state "
                            "FROM agent_actions WHERE agent_action_id=?", (action_key,)).fetchone()
        if action is None:
            raise ValidationError("A persisted AgentAction is required")
        aa_agent, aa_code, target, aa_context, bound, aa_state = action
        if (aa_agent != agent or aa_code != self.ACTION_CODE or target != activity or
                aa_context != activity or bound != grant_key):
            raise ValidationError("AgentAction does not match Finance policy operation and grant")
        if aa_state != "approved":
            raise ValidationError("Finance policy changes require an approved AgentAction")
        approval = db.execute("SELECT approver_person_id FROM approvals WHERE agent_action_id=? "
                              "AND state='APPROVED' AND approver_person_id IS NOT NULL "
                              "ORDER BY decision_at DESC LIMIT 1", (action_key,)).fetchone()
        if approval is None:
            raise ValidationError("Finance policy changes require recorded human approval")
        if db.execute("SELECT 1 FROM persons WHERE person_id=? AND state='active'",
                      (approval[0],)).fetchone() is None:
            raise ValidationError("Finance policy approver must be an active persisted Person")
        if db.execute("SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent,)).fetchone() is None:
            raise ValidationError("Finance policy Agent must be active")
        return approval[0]

    @staticmethod
    def _record(activity, row):
        if row is None:
            return FinancialPolicySettings(activity_id=UUID(activity))
        enabled, default, places, cardinality, voucher, strict, version, updated, actor = row
        return FinancialPolicySettings(UUID(activity), tuple(enabled.split(",")), default, places,
                                       cardinality, voucher, bool(strict), version, updated, UUID(actor))

    def get_settings(self, db, *, activity_id):
        activity = str(self._uuid(activity_id, "Activity identifier"))
        if db.execute("SELECT 1 FROM activities WHERE activity_id=?", (activity,)).fetchone() is None:
            raise ValidationError("Finance policy Activity does not exist")
        row = db.execute("SELECT enabled_currencies,default_currency,display_decimal_places,"
                         "settlement_cardinality,posted_voucher_policy,require_full_cycle_validation,"
                         "version_no,updated_at,updated_by_person_id FROM financial_policy_settings "
                         "WHERE activity_id=?", (activity,)).fetchone()
        return self._record(activity, row)

    def update_settings(self, db, *, activity_id, enabled_currencies=("SAR", "YER", "USD"),
                        default_currency=DEFAULT_CURRENCY, display_decimal_places=2,
                        settlement_cardinality="single", posted_voucher_policy="reversal_only",
                        require_full_cycle_validation=True, authorization_grant_id, agent_action_id, now=None,
                        expected_version_no=None):
        activity = str(self._uuid(activity_id, "Activity identifier"))
        policy = self.validate_policy(enabled_currencies=enabled_currencies, default_currency=default_currency,
                                      display_decimal_places=display_decimal_places,
                                      settlement_cardinality=settlement_cardinality,
                                      posted_voucher_policy=posted_voucher_policy,
                                      require_full_cycle_validation=require_full_cycle_validation)
        if expected_version_no is not None and (
                type(expected_version_no) is not int or expected_version_no < 0):
            raise ValidationError("expected_version_no must be a non-negative integer")
        stamp = self._timestamp(now if now is not None else datetime.now(timezone.utc),
                                "Finance policy timestamp")
        with self._atomic(db):
            if db.execute("SELECT 1 FROM activities WHERE activity_id=?", (activity,)).fetchone() is None:
                raise ValidationError("Finance policy Activity does not exist")
            actor = self._authorize(db, activity, authorization_grant_id, agent_action_id, stamp)
            prior = db.execute("SELECT version_no FROM financial_policy_settings WHERE activity_id=?",
                               (activity,)).fetchone()
            current_version = 0 if prior is None else prior[0]
            if expected_version_no is not None and expected_version_no != current_version:
                raise ValidationError(
                    f"Stale Finance policy settings version: expected {expected_version_no}, "
                    f"current version is {current_version}"
                )
            version = current_version + 1
            db.execute("INSERT INTO financial_policy_settings(activity_id,enabled_currencies,default_currency,"
                       "display_decimal_places,settlement_cardinality,posted_voucher_policy,"
                       "require_full_cycle_validation,version_no,updated_at,updated_by_person_id) "
                       "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(activity_id) DO UPDATE SET "
                       "enabled_currencies=excluded.enabled_currencies,default_currency=excluded.default_currency,"
                       "display_decimal_places=excluded.display_decimal_places,"
                       "settlement_cardinality=excluded.settlement_cardinality,"
                       "posted_voucher_policy=excluded.posted_voucher_policy,"
                       "require_full_cycle_validation=excluded.require_full_cycle_validation,"
                       "version_no=excluded.version_no,updated_at=excluded.updated_at,"
                       "updated_by_person_id=excluded.updated_by_person_id",
                       (activity, ",".join(policy["enabled_currencies"]), policy["default_currency"],
                        policy["display_decimal_places"], policy["settlement_cardinality"],
                        policy["posted_voucher_policy"], int(policy["require_full_cycle_validation"]),
                        version, stamp, actor))
            payload = dict(policy)
            payload["enabled_currencies"] = list(policy["enabled_currencies"])
            db.execute("INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
                       "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                       "VALUES(?,?,?,?,?,?,?,?,?,?)",
                       (str(uuid4()), "FinancePolicySettings", activity,
                        "created" if prior is None else "updated", stamp, activity,
                        None if prior is None else str(prior[0]), str(version),
                        json.dumps(payload, sort_keys=True, separators=(",", ":")), stamp))
        return self.get_settings(db, activity_id=activity)
