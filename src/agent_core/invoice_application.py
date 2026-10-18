"""Persisted Commerce Invoice lifecycle, separate from payment and financial recognition."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.domain_commerce import Invoice, InvoiceState
from agent_core.application import SaleApplication
from agent_core.shared import ValidationError


@dataclass(frozen=True)
class InvoiceRecord:
    invoice: Invoice
    sale_id: UUID
    obligation_id: UUID | None
    issuer_ref: str
    invoice_number: str
    issue_at: str
    due_at: str | None
    version_no: int


class InvoiceApplication:
    """Persist issued invoices and void them without deleting commercial history.

    This service does not create obligations, payments, settlements, or ledger entries.
    Invoice actions require an exact persisted AuthorizationGrant scoped to the parent
    Sale and its Activity, using the existing application authorization contract.
    """

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
    def _timestamp(value, label, *, optional=False):
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

    def _load(self, connection, invoice_id):
        key = str(self._uuid(invoice_id, "Invoice identifier"))
        row = connection.execute(
            "SELECT invoice_id, sale_id, obligation_id, state, issuer_ref, invoice_number, "
            "issue_at, due_at, version_no FROM invoices WHERE invoice_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Invoice does not exist")
        iid, sale_id, obligation_id, state, issuer, number, issue_at, due_at, version = row
        return InvoiceRecord(
            Invoice(UUID(sale_id), id=UUID(iid), state=InvoiceState(state)),
            UUID(sale_id), UUID(obligation_id) if obligation_id else None,
            issuer, number, issue_at, due_at, version,
        )

    def create_invoice(
        self, connection, *, sale_id, actor_context_ref, authorization_grant_id,
        issuer_ref, invoice_number, issue_at=None, due_at=None, obligation_id=None,
        invoice_id=None, now=None,
    ) -> InvoiceRecord:
        sale_key = str(self._uuid(sale_id, "Sale identifier"))
        actor_key = str(self._uuid(actor_context_ref, "Actor identifier"))
        grant_key = self._uuid(authorization_grant_id, "AuthorizationGrant identifier")
        issuer_key = str(self._uuid(issuer_ref, "Invoice issuer identifier"))
        obligation_key = str(self._uuid(obligation_id, "Obligation identifier")) if obligation_id is not None else None
        key = str(self._uuid(invoice_id, "Invoice identifier")) if invoice_id is not None else str(uuid4())
        # Use one clock reading for both defaults; separate datetime.now() calls
        # can make the issue timestamp a few microseconds later than creation.
        default_instant = now if now is not None else datetime.now(timezone.utc)
        timestamp = self._timestamp(default_instant, "Invoice creation timestamp")
        issued = self._timestamp(issue_at if issue_at is not None else default_instant, "Invoice issue timestamp")
        due = self._timestamp(due_at, "Invoice due timestamp", optional=True)
        created_instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        issue_instant = datetime.fromisoformat(issued.replace("Z", "+00:00"))
        if issue_instant > created_instant:
            raise ValidationError("Invoice issue timestamp cannot follow creation timestamp")
        if due is not None:
            due_instant = datetime.fromisoformat(due.replace("Z", "+00:00"))
            if due_instant < issue_instant:
                raise ValidationError("Invoice due timestamp cannot precede issue timestamp")
        if not isinstance(invoice_number, str) or not invoice_number.strip():
            raise ValidationError("Invoice number must be a non-empty string")
        invoice_number = invoice_number.strip()

        with self._atomic(connection):
            # Read and authorize inside the same transaction as the invoice write.
            # This prevents a grant revocation or Sale-context change between authorization and persistence.
            sale = connection.execute(
                "SELECT s.activity_id, o.activity_id FROM sales s "
                "LEFT JOIN offerings o ON o.offering_id=s.offering_id WHERE s.sale_id=?",
                (sale_key,),
            ).fetchone()
            if sale is None:
                raise ValidationError("Invoice requires an existing Sale")
            activity_key, offering_activity_key = sale
            if offering_activity_key is None or activity_key != offering_activity_key:
                raise ValidationError(
                    "Sale Activity does not match its Offering Activity; explicit reconciliation is required"
                )
            SaleApplication()._authorize(
                connection, grant_key, actor_key, "issue_invoice",
                sale_key, activity_key, timestamp,
            )
            if connection.execute(
                "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (issuer_key,)
            ).fetchone() is None:
                raise ValidationError("Invoice issuer must be an active persisted Person")
            invoice = Invoice(UUID(sale_key), id=UUID(key), state=InvoiceState.ISSUED)
            connection.execute(
                "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,"
                "issue_at,due_at,created_at,updated_at,version_no) VALUES(?,?,?,'issued',?,?,?,?,?,?,1)",
                (key, sale_key, obligation_key, issuer_key, invoice_number, issued, due, timestamp, timestamp),
            )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", key, "invoice_state_transition", timestamp, actor_key,
                 None, f"invoice-state:{key}:v1", "state:issued", timestamp),
            )
        return self._load(connection, key)

    def get_invoice(self, connection, invoice_id) -> InvoiceRecord:
        return self._load(connection, invoice_id)

    def void_invoice(
        self, connection, invoice_id, *, actor_context_ref, authorization_grant_id, now=None,
        expected_version,
    ) -> InvoiceRecord:
        key = str(self._uuid(invoice_id, "Invoice identifier"))
        actor_key = str(self._uuid(actor_context_ref, "Actor identifier"))
        if type(expected_version) is not int or expected_version < 1:
            raise ValidationError("Invoice expected_version must be a positive integer")
        timestamp = self._timestamp(now if now is not None else datetime.now(timezone.utc), "Invoice void timestamp")
        with self._atomic(connection):
            record = self._load(connection, key)
            created_at = connection.execute(
                "SELECT created_at FROM invoices WHERE invoice_id=?", (key,)
            ).fetchone()[0]
            void_instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            created_instant = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
            if void_instant < created_instant:
                raise ValidationError("Invoice void timestamp cannot precede creation timestamp")
            SaleApplication()._authorize(
                connection, authorization_grant_id, actor_key, "void_invoice",
                str(record.sale_id),
                connection.execute("SELECT activity_id FROM sales WHERE sale_id=?", (str(record.sale_id),)).fetchone()[0],
                timestamp,
            )
            if record.version_no != expected_version:
                raise ValidationError("Invoice version conflict; reload before retrying")
            if record.invoice.state is not InvoiceState.ISSUED:
                raise ValidationError("Only an issued Invoice can be voided by this operation")
            next_version = record.version_no + 1
            cursor = connection.execute(
                "UPDATE invoices SET state='void', updated_at=?, version_no=? "
                "WHERE invoice_id=? AND version_no=? AND state='issued'",
                (timestamp, next_version, key, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Concurrent Invoice update detected")
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "commerce", key, "invoice_state_transition", timestamp, actor_key,
                 f"invoice-state:{key}:v{record.version_no}", f"invoice-state:{key}:v{next_version}",
                 "state:void", timestamp),
            )
        return self._load(connection, key)
