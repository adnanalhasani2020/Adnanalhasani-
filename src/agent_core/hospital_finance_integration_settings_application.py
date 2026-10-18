"""Versioned administrator settings for Hospital-Finance correlation.

Settings are configuration, not financial authorization. The consumer remains opt-in;
only existing, fully settled Finance records can be recognized by the current path.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4
from contextlib import contextmanager

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class HospitalFinanceIntegrationSettings:
    settings_id: UUID
    activity_id: UUID
    hospital_scope_ref: str
    finance_person_id: UUID
    financial_account_id: UUID
    invoice_policy: str
    payment_trigger: str
    partial_payment_policy: str
    currency_policy: str
    status: str
    version_no: int
    proposed_by_person_id: UUID
    proposed_at: str
    approved_by_person_id: UUID | None = None
    approved_at: str | None = None


class HospitalFinanceIntegrationSettingsApplication:
    """Persist proposals and require a distinct active Finance Person to activate them."""

    @staticmethod
    def _uuid(value, label):
        try:
            return str(UUID(str(value)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _stamp(value):
        if value is None:
            value = datetime.now(timezone.utc)
        elif isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError("timestamp must be ISO-8601") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError("timestamp must include a timezone")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _atomic(db):
        @contextmanager
        def transaction():
            name = "hospital_finance_settings_" + uuid4().hex
            db.execute(f"SAVEPOINT {name}")
            try:
                yield
            except BaseException:
                db.execute(f"ROLLBACK TO SAVEPOINT {name}")
                db.execute(f"RELEASE SAVEPOINT {name}")
                raise
            else:
                db.execute(f"RELEASE SAVEPOINT {name}")
        return transaction()

    @staticmethod
    def _person_is_active(db, person_id):
        return db.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (person_id,)
        ).fetchone() is not None

    @staticmethod
    def _account_owner(db, account_id):
        return db.execute(
            "SELECT person_id,state FROM financial_accounts WHERE financial_account_id=?",
            (account_id,),
        ).fetchone()

    @classmethod
    def _record(cls, row):
        if row is None:
            return None
        return HospitalFinanceIntegrationSettings(
            settings_id=UUID(row[0]), activity_id=UUID(row[1]), hospital_scope_ref=row[2],
            finance_person_id=UUID(row[3]), financial_account_id=UUID(row[4]),
            invoice_policy=row[5], payment_trigger=row[6], partial_payment_policy=row[7],
            currency_policy=row[8], status=row[9], version_no=row[10],
            proposed_by_person_id=UUID(row[11]), proposed_at=row[12],
            approved_by_person_id=UUID(row[13]) if row[13] else None, approved_at=row[14],
        )

    _SELECT = (
        "SELECT settings_id,activity_id,hospital_scope_ref,finance_person_id,financial_account_id,"
        "invoice_policy,payment_trigger,partial_payment_policy,currency_policy,status,version_no,"
        "proposed_by_person_id,proposed_at,approved_by_person_id,approved_at "
        "FROM hospital_finance_integration_settings"
    )

    def propose(self, db, *, activity_id, hospital_scope_ref, finance_person_id,
                financial_account_id, proposed_by_person_id, invoice_policy="operational_only",
                payment_trigger="manual_review_only", partial_payment_policy="reject_until_supported",
                currency_policy="require_exact_match", now=None):
        activity = self._uuid(activity_id, "Activity identifier")
        person = self._uuid(finance_person_id, "Finance Person identifier")
        account = self._uuid(financial_account_id, "Financial Account identifier")
        proposer = self._uuid(proposed_by_person_id, "Proposer Person identifier")
        scope = str(hospital_scope_ref).strip() if isinstance(hospital_scope_ref, str) else ""
        if not scope or len(scope) > 160:
            raise ValidationError("hospital_scope_ref must contain 1-160 characters")
        if invoice_policy not in {"operational_only", "finance_obligation_review_required"}:
            raise ValidationError("unsupported invoice_policy")
        if payment_trigger not in {"fully_settled_existing_finance_payment", "manual_review_only"}:
            raise ValidationError("unsupported payment_trigger")
        if partial_payment_policy != "reject_until_supported":
            raise ValidationError("partial payments remain blocked until remaining-balance support is implemented")
        if currency_policy != "require_exact_match":
            raise ValidationError("currency conversion and rounding are not enabled for Hospital recognition")
        activity_row = db.execute(
            "SELECT state FROM activities WHERE activity_id=?", (activity,)
        ).fetchone()
        if activity_row is None:
            raise ValidationError("Finance Activity does not exist")
        if activity_row[0] != "active":
            raise ValidationError("Finance Activity must be active to propose integration settings")
        if not self._person_is_active(db, person) or not self._person_is_active(db, proposer):
            raise ValidationError("Finance Person and proposer must be active persisted Persons")
        owner = self._account_owner(db, account)
        if owner is None or owner[1] != "active":
            raise ValidationError("Financial Account must exist and be active")
        if owner[0] != person:
            raise ValidationError("Financial Account must belong to the selected Finance Person")
        stamp = self._stamp(now)
        with self._atomic(db):
            prior = db.execute(
                "SELECT COALESCE(MAX(version_no),0) FROM hospital_finance_integration_settings "
                "WHERE activity_id=? AND hospital_scope_ref=?", (activity, scope)
            ).fetchone()[0]
            settings_id = str(uuid4())
            db.execute(
                "INSERT INTO hospital_finance_integration_settings(settings_id,activity_id,hospital_scope_ref,"
                "finance_person_id,financial_account_id,invoice_policy,payment_trigger,partial_payment_policy,"
                "currency_policy,status,version_no,proposed_by_person_id,proposed_at,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,'pending_approval',?,?,?,?)",
                (settings_id, activity, scope, person, account, invoice_policy, payment_trigger,
                 partial_payment_policy, currency_policy, prior + 1, proposer, stamp, stamp),
            )
        return self.get_by_id(db, settings_id=settings_id)

    def get_by_id(self, db, *, settings_id):
        ident = self._uuid(settings_id, "Settings identifier")
        return self._record(db.execute(self._SELECT + " WHERE settings_id=?", (ident,)).fetchone())

    def get_active(self, db, *, activity_id, hospital_scope_ref):
        activity = self._uuid(activity_id, "Activity identifier")
        scope = str(hospital_scope_ref).strip() if isinstance(hospital_scope_ref, str) else ""
        if not scope:
            raise ValidationError("hospital_scope_ref is required")
        record = self._record(db.execute(
            self._SELECT + " WHERE activity_id=? AND hospital_scope_ref=? AND status='active'",
            (activity, scope),
        ).fetchone())
        if record is None:
            return None
        activity_row = db.execute("SELECT state FROM activities WHERE activity_id=?", (activity,)).fetchone()
        owner = self._account_owner(db, str(record.financial_account_id))
        if (activity_row is None or activity_row[0] != "active"
                or not self._person_is_active(db, str(record.finance_person_id))
                or owner is None or owner[1] != "active" or owner[0] != str(record.finance_person_id)):
            raise ValidationError("Active Hospital-Finance settings reference inactive or inconsistent Finance records")
        return record

    def approve(self, db, *, settings_id, approved_by_person_id, now=None, note=None):
        ident = self._uuid(settings_id, "Settings identifier")
        approver = self._uuid(approved_by_person_id, "Approver Person identifier")
        stamp = self._stamp(now)
        with self._atomic(db):
            row = db.execute(
                "SELECT activity_id,hospital_scope_ref,finance_person_id,financial_account_id,"
                "status,proposed_by_person_id FROM hospital_finance_integration_settings WHERE settings_id=?",
                (ident,),
            ).fetchone()
            if row is None:
                raise ValidationError("Hospital-Finance settings proposal does not exist")
            activity, scope, person, account, status, proposer = row
            if status != "pending_approval":
                raise ValidationError("Only a pending settings proposal can be approved")
            activity_row = db.execute(
                "SELECT state FROM activities WHERE activity_id=?", (activity,)
            ).fetchone()
            if activity_row is None or activity_row[0] != "active":
                raise ValidationError("Finance Activity must remain active to approve integration settings")
            if proposer == approver:
                raise ValidationError("The proposer cannot approve their own settings")
            if not self._person_is_active(db, approver):
                raise ValidationError("Approver must be an active persisted Finance Person")
            if not self._person_is_active(db, person):
                raise ValidationError("Mapped Finance Person is no longer active")
            owner = self._account_owner(db, account)
            if owner is None or owner[1] != "active" or owner[0] != person:
                raise ValidationError("Mapped Financial Account is inactive or no longer belongs to the selected Person")
            db.execute(
                "UPDATE hospital_finance_integration_settings SET status='superseded' "
                "WHERE activity_id=? AND hospital_scope_ref=? AND status='active'", (activity, scope)
            )
            changed = db.execute(
                "UPDATE hospital_finance_integration_settings SET status='active',approved_by_person_id=?,"
                "approved_at=?,approval_note=? WHERE settings_id=? AND status='pending_approval'",
                (approver, stamp, str(note or "")[:500], ident),
            ).rowcount
            if changed != 1:
                raise ValidationError("Settings proposal changed concurrently; reload and retry")
        return self.get_by_id(db, settings_id=ident)
