"""Supplier invoice capture with strict matching to persisted purchase receipt evidence."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from uuid import UUID, uuid4

from agent_core.purchase_application import PurchaseApplication
from agent_core.shared import ValidationError


_MAX_INTEGER = 2**63 - 1


@dataclass(frozen=True)
class SupplierInvoiceLine:
    purchase_line_id: UUID
    quantity_units: int
    unit_price_minor: int


@dataclass(frozen=True)
class SupplierInvoiceLineRecord:
    purchase_line_id: UUID
    quantity_units: int
    unit_price_minor: int
    line_total_minor: int


@dataclass(frozen=True)
class SupplierInvoicePriceVariance:
    purchase_line_id: UUID
    quantity_units: int
    expected_unit_price_minor: int
    invoiced_unit_price_minor: int
    unit_variance_minor: int
    total_variance_minor: int
    currency_code: str


@dataclass(frozen=True)
class SupplierInvoiceRecord:
    id: UUID
    purchase_id: UUID
    supplier_ref: str
    invoice_number: str
    currency_code: str
    subtotal_minor: int
    tax_minor: int
    freight_minor: int
    total_minor: int
    state: str
    recorded_at: str
    recorded_by_ref: UUID
    version_no: int
    lines: tuple[SupplierInvoiceLineRecord, ...]


class SupplierInvoiceApplication:
    """Record a supplier invoice only up to quantities already evidenced as received.

    This service does not create obligations, payments, settlements, inventory changes,
    or ledger entries. Price differences are retained as invoice facts for review.
    """

    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value):
        if value is None:
            value = datetime.now(timezone.utc)
        elif isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError("Supplier invoice timestamp must be ISO-8601") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError("Supplier invoice timestamp must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _key(value):
        if not isinstance(value, str) or not value.strip() or len(value.strip()) > 200:
            raise ValidationError("idempotency_key must be a non-empty string of at most 200 characters")
        return value.strip()

    @staticmethod
    def _currency(value):
        if (not isinstance(value, str) or len(value) != 3 or not value.isascii()
                or not value.isalpha() or value != value.upper()):
            raise ValidationError("currency_code must be a 3-letter uppercase code")
        return value

    @staticmethod
    def _amount(value, label):
        if type(value) is not int or not 0 <= value <= _MAX_INTEGER:
            raise ValidationError(f"{label} must be a non-negative integer within SQLite limits")
        return value

    @staticmethod
    @contextmanager
    def _atomic(connection):
        if connection.in_transaction:
            savepoint = "supplier_invoice_" + uuid4().hex
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

    def _load(self, connection, invoice_id):
        key = str(self._uuid(invoice_id, "Supplier invoice identifier"))
        row = connection.execute(
            "SELECT supplier_invoice_id,purchase_id,supplier_ref,invoice_number,currency_code,"
            "subtotal_minor,tax_minor,freight_minor,total_minor,state,recorded_at,recorded_by_ref,version_no "
            "FROM supplier_invoices WHERE supplier_invoice_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Supplier invoice does not exist")
        (iid, purchase_id, supplier, number, currency, subtotal, tax, freight, total,
         state, recorded_at, actor, version) = row
        persisted_invoice_id = self._uuid(iid, "Persisted supplier invoice identifier")
        persisted_purchase_id = self._uuid(purchase_id, "Persisted supplier invoice Purchase identifier")
        persisted_actor_id = self._uuid(actor, "Persisted supplier invoice actor identifier")
        if not isinstance(recorded_at, str):
            raise ValidationError("Persisted supplier invoice timestamp is invalid")
        try:
            self._timestamp(recorded_at)
        except ValidationError as exc:
            raise ValidationError("Persisted supplier invoice timestamp is invalid") from exc
        if state != "recorded":
            raise ValidationError("Persisted supplier invoice has an unsupported state")
        if not isinstance(number, str) or not number.strip():
            raise ValidationError("Persisted supplier invoice number is invalid")
        self._currency(currency)
        for amount, label in (
            (subtotal, "persisted subtotal_minor"), (tax, "persisted tax_minor"),
            (freight, "persisted freight_minor"), (total, "persisted total_minor"),
        ):
            self._amount(amount, label)
        if type(version) is not int or version <= 0:
            raise ValidationError("Persisted supplier invoice version is invalid")
        line_rows = connection.execute(
            "SELECT purchase_line_id,quantity_units,unit_price_minor,line_total_minor "
            "FROM supplier_invoice_lines WHERE supplier_invoice_id=? ORDER BY rowid", (key,),
        ).fetchall()
        lines = tuple(SupplierInvoiceLineRecord(
            self._uuid(line_id, "Persisted supplier invoice Purchase line identifier"), quantity, unit_price, line_total
        ) for line_id, quantity, unit_price, line_total in line_rows)
        if not lines:
            raise ValidationError("Persisted supplier invoice must contain at least one line")
        for line in lines:
            if (type(line.quantity_units) is not int or line.quantity_units <= 0
                    or type(line.unit_price_minor) is not int
                    or not 0 <= line.unit_price_minor <= _MAX_INTEGER
                    or type(line.line_total_minor) is not int
                    or not 0 <= line.line_total_minor <= _MAX_INTEGER):
                raise ValidationError("Persisted supplier invoice line contains invalid numeric facts")
            if (line.quantity_units > _MAX_INTEGER
                    or (line.unit_price_minor and line.quantity_units > _MAX_INTEGER // line.unit_price_minor)
                    or line.line_total_minor != line.quantity_units * line.unit_price_minor):
                raise ValidationError("Supplier invoice line total disagrees with quantity and unit price")
        if sum(line.line_total_minor for line in lines) != subtotal:
            raise ValidationError("Supplier invoice line totals disagree with persisted subtotal")
        if total != subtotal + tax + freight:
            raise ValidationError("Supplier invoice total disagrees with persisted components")

        purchase = connection.execute(
            "SELECT supplier_ref FROM purchases WHERE purchase_id=?", (purchase_id,)
        ).fetchone()
        if purchase is None or purchase[0] != supplier:
            raise ValidationError("Supplier invoice supplier does not match its Purchase")
        for line in lines:
            baseline = connection.execute(
                "SELECT unit_cost_minor,currency_code FROM purchase_lines "
                "WHERE purchase_line_id=? AND purchase_id=?",
                (str(line.purchase_line_id), purchase_id),
            ).fetchone()
            if baseline is None:
                raise ValidationError("Supplier invoice line references a missing Purchase line")
            expected_price, expected_currency = baseline
            if expected_price is not None and expected_currency != currency:
                raise ValidationError("Persisted supplier invoice currency differs from Purchase line currency")
        history = connection.execute(
            "SELECT actor_context_ref,current_version_ref,change_payload_ref FROM domain_history "
            "WHERE owner_domain='procurement' AND target_ref=? AND change_type='supplier_invoice_recorded'",
            (key,),
        ).fetchall()
        expected_payload = f"purchase:{purchase_id};total_minor:{total};currency:{currency}"
        if len(history) != 1 or history[0] != (
            actor, f"supplier-invoice:{key}:v{version}", expected_payload
        ):
            raise ValidationError("Supplier invoice history disagrees with persisted invoice facts")

        if state == "recorded":
            for line in lines:
                totals = connection.execute(
                    "SELECT COALESCE(SUM(sil.quantity_units),0),pl.received_quantity_units "
                    "FROM supplier_invoice_lines sil "
                    "JOIN supplier_invoices si ON si.supplier_invoice_id=sil.supplier_invoice_id "
                    "JOIN purchase_lines pl ON pl.purchase_line_id=sil.purchase_line_id "
                    "WHERE sil.purchase_line_id=? AND sil.purchase_id=? AND si.state='recorded' "
                    "GROUP BY pl.received_quantity_units",
                    (str(line.purchase_line_id), purchase_id),
                ).fetchone()
                if totals is None or totals[0] > totals[1]:
                    raise ValidationError("Persisted supplier invoices exceed received Purchase quantity")
        return SupplierInvoiceRecord(
            persisted_invoice_id, persisted_purchase_id, supplier, number, currency,
            subtotal, tax, freight, total, state, recorded_at, persisted_actor_id, version, lines,
        )

    def get_supplier_invoice_price_variances(self, connection, supplier_invoice_id):
        """Return explicit price differences where the purchase line has a comparable baseline.

        Positive variance means the invoice price is higher than the recorded purchase price;
        negative means lower. Lines without a recorded purchase price are omitted because no
        reliable baseline exists. This is a read-only report and never approves or posts finance.
        """
        invoice = self._load(connection, supplier_invoice_id)
        variances = []
        for line in invoice.lines:
            row = connection.execute(
                "SELECT unit_cost_minor,currency_code FROM purchase_lines "
                "WHERE purchase_line_id=? AND purchase_id=?",
                (str(line.purchase_line_id), str(invoice.purchase_id)),
            ).fetchone()
            if row is None:
                raise ValidationError("Supplier invoice line references a missing Purchase line")
            expected_price, expected_currency = row
            if expected_price is None:
                continue
            if expected_currency != invoice.currency_code:
                raise ValidationError("Cannot compare supplier invoice price across currencies")
            unit_delta = line.unit_price_minor - expected_price
            if unit_delta and line.quantity_units > _MAX_INTEGER // abs(unit_delta):
                raise ValidationError("Supplier invoice price variance exceeds SQLite integer limits")
            variances.append(SupplierInvoicePriceVariance(
                purchase_line_id=line.purchase_line_id,
                quantity_units=line.quantity_units,
                expected_unit_price_minor=expected_price,
                invoiced_unit_price_minor=line.unit_price_minor,
                unit_variance_minor=unit_delta,
                total_variance_minor=unit_delta * line.quantity_units,
                currency_code=invoice.currency_code,
            ))
        return tuple(variances)

    def get_supplier_invoice(self, connection, supplier_invoice_id):
        return self._load(connection, supplier_invoice_id)

    def record_supplier_invoice(
        self, connection, *, purchase_id, actor_context_ref, authorization_grant_id,
        invoice_number, currency_code, lines, idempotency_key, tax_minor=0,
        freight_minor=0, now=None,
    ):
        purchase_key = str(self._uuid(purchase_id, "Purchase identifier"))
        actor_key = str(self._uuid(actor_context_ref, "Actor identifier"))
        timestamp = self._timestamp(now)
        key = self._key(idempotency_key)
        currency = self._currency(currency_code)
        tax = self._amount(tax_minor, "tax_minor")
        freight = self._amount(freight_minor, "freight_minor")
        if not isinstance(invoice_number, str) or not invoice_number.strip():
            raise ValidationError("Supplier invoice number is required")
        number = invoice_number.strip()
        if not isinstance(lines, (tuple, list)) or not lines:
            raise ValidationError("Supplier invoice requires at least one line")

        normalized = []
        seen = set()
        for item in lines:
            if isinstance(item, SupplierInvoiceLine):
                line = item
            elif isinstance(item, dict):
                line = SupplierInvoiceLine(
                    purchase_line_id=self._uuid(item.get("purchase_line_id"), "Purchase line identifier"),
                    quantity_units=item.get("quantity_units"),
                    unit_price_minor=item.get("unit_price_minor"),
                )
            else:
                raise ValidationError("Supplier invoice lines must be SupplierInvoiceLine values or dictionaries")
            line_id = self._uuid(line.purchase_line_id, "Purchase line identifier")
            if line_id in seen:
                raise ValidationError("Supplier invoice cannot repeat a purchase line")
            seen.add(line_id)
            if type(line.quantity_units) is not int or line.quantity_units <= 0:
                raise ValidationError("invoiced quantity must be a positive integer")
            price = self._amount(line.unit_price_minor, "unit_price_minor")
            if line.quantity_units > _MAX_INTEGER or price and line.quantity_units > _MAX_INTEGER // price:
                raise ValidationError("Supplier invoice line total exceeds SQLite integer limits")
            normalized.append(SupplierInvoiceLine(line_id, line.quantity_units, price))

        fingerprint_payload = {
            "operation": "record_supplier_invoice", "purchase_id": purchase_key,
            "actor": actor_key, "invoice_number": number, "currency_code": currency,
            "tax_minor": tax, "freight_minor": freight,
            "lines": [{"purchase_line_id": str(x.purchase_line_id),
                       "quantity_units": x.quantity_units, "unit_price_minor": x.unit_price_minor}
                      for x in normalized],
        }
        fingerprint = hashlib.sha256(json.dumps(
            fingerprint_payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True
        ).encode("utf-8")).hexdigest()

        with self._atomic(connection):
            purchase_app = PurchaseApplication()
            purchase = purchase_app.get_purchase(connection, purchase_key).purchase
            purchase_app._authorize(
                connection, authorization_grant_id, actor_key, "record_supplier_invoice",
                purchase_key, purchase.activity_id, timestamp,
            )
            existing = connection.execute(
                "SELECT supplier_invoice_id,request_fingerprint FROM supplier_invoices WHERE idempotency_key=?",
                (key,),
            ).fetchone()
            if existing:
                if existing[1] != fingerprint:
                    raise ValidationError("idempotency_key was already used with a different supplier invoice")
                return self._load(connection, existing[0])

            if purchase.state.value not in {"ordered", "partially_received", "received"}:
                raise ValidationError("Supplier invoice requires an ordered Purchase")
            purchase_row = connection.execute(
                "SELECT supplier_ref FROM purchases WHERE purchase_id=?", (purchase_key,)
            ).fetchone()
            supplier_ref = purchase_row[0]
            purchase_lines = {str(line.id): line for line in purchase.lines}
            subtotal = 0
            for line in normalized:
                purchase_line = purchase_lines.get(str(line.purchase_line_id))
                if purchase_line is None:
                    raise ValidationError("Supplier invoice line does not belong to this Purchase")
                if line.quantity_units > purchase_line.received_quantity_units:
                    raise ValidationError("Supplier invoice quantity exceeds received quantity for its Purchase line")
                if purchase_line.unit_cost_minor is not None and purchase_line.currency_code != currency:
                    raise ValidationError("Supplier invoice currency differs from the Purchase line currency")
                already = connection.execute(
                    "SELECT COALESCE(SUM(sil.quantity_units),0) FROM supplier_invoice_lines sil "
                    "JOIN supplier_invoices si ON si.supplier_invoice_id=sil.supplier_invoice_id "
                    "WHERE sil.purchase_line_id=? AND sil.purchase_id=? AND si.state='recorded'",
                    (str(line.purchase_line_id), purchase_key),
                ).fetchone()[0]
                if already + line.quantity_units > purchase_line.received_quantity_units:
                    raise ValidationError("Supplier invoice quantity exceeds uninvoiced received quantity")
                line_total = line.quantity_units * line.unit_price_minor
                if subtotal > _MAX_INTEGER - line_total:
                    raise ValidationError("Supplier invoice subtotal exceeds SQLite integer limits")
                subtotal += line_total
            if subtotal > _MAX_INTEGER - tax or subtotal + tax > _MAX_INTEGER - freight:
                raise ValidationError("Supplier invoice total exceeds SQLite integer limits")
            total = subtotal + tax + freight
            invoice_id = str(uuid4())
            connection.execute(
                "INSERT INTO supplier_invoices(supplier_invoice_id,purchase_id,supplier_ref,invoice_number,"
                "currency_code,subtotal_minor,tax_minor,freight_minor,total_minor,state,recorded_at,"
                "recorded_by_ref,idempotency_key,request_fingerprint,version_no) "
                "VALUES(?,?,?,?,?,?,?,?,?,'recorded',?,?,?,?,1)",
                (invoice_id, purchase_key, supplier_ref, number, currency, subtotal, tax, freight,
                 total, timestamp, actor_key, key, fingerprint),
            )
            for line in normalized:
                connection.execute(
                    "INSERT INTO supplier_invoice_lines(supplier_invoice_line_id,supplier_invoice_id,purchase_id,"
                    "purchase_line_id,quantity_units,unit_price_minor,line_total_minor) VALUES(?,?,?,?,?,?,?)",
                    (str(uuid4()), invoice_id, purchase_key, str(line.purchase_line_id),
                     line.quantity_units, line.unit_price_minor, line.quantity_units * line.unit_price_minor),
                )
            connection.execute(
                "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
                "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(uuid4()), "procurement", invoice_id, "supplier_invoice_recorded", timestamp,
                 actor_key, None, f"supplier-invoice:{invoice_id}:v1",
                 f"purchase:{purchase_key};total_minor:{total};currency:{currency}", timestamp),
            )
        return self._load(connection, invoice_id)
