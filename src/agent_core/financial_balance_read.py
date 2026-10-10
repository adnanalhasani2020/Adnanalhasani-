"""Read-only persisted Balance projection over currently posted Finance ledger entries.

The result is derived, never persisted. Only currently posted Ledger Entries tied
to recognized Financial Transactions are included; pending operational records
and Payment/Settlement state are not treated as financial truth.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class FinancialBalance:
    financial_account_id: UUID
    currency_code: str
    amount_minor: int
    as_of: datetime
    entry_count: int


class FinancialBalanceReader:
    """Derive a currency-specific balance from recognized, posted ledger facts."""

    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _instant(value):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError("Balance as_of must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError("Balance as_of must be timezone-aware")
        return value.astimezone(timezone.utc)

    def read(self, connection, financial_account_id, currency_code, *, as_of=None) -> FinancialBalance:
        account_id = self._uuid(financial_account_id, "Financial Account identifier")
        if not isinstance(currency_code, str) or not currency_code.strip():
            raise ValidationError("Balance currency_code is required")
        currency = currency_code.strip()
        instant = self._instant(datetime.now(timezone.utc) if as_of is None else as_of)
        cutoff = instant.isoformat().replace("+00:00", "Z")

        if connection.execute(
            "SELECT 1 FROM financial_accounts WHERE financial_account_id=?", (str(account_id),)
        ).fetchone() is None:
            raise ValidationError("Financial Account does not exist")

        # A mismatched Ledger Entry / Financial Transaction is persisted corruption;
        # fail closed rather than silently misstate the derived balance.
        mismatch = connection.execute(
            "SELECT 1 FROM ledger_entries le "
            "JOIN financial_transactions ft ON ft.financial_transaction_id=le.financial_transaction_id "
            "WHERE le.financial_account_id=? AND le.state='posted' AND le.posted_at<=? "
            "AND ft.state='recognized' AND "
            "(le.financial_account_id<>ft.financial_account_id OR le.currency_code<>ft.currency_code) "
            "AND (le.currency_code=? OR ft.currency_code=?) LIMIT 1",
            (str(account_id), cutoff, currency, currency),
        ).fetchone()
        if mismatch is not None:
            raise ValidationError("Ledger Entry does not match its recognized Financial Transaction")

        row = connection.execute(
            "SELECT COALESCE(SUM(le.amount_minor),0), COUNT(*) "
            "FROM ledger_entries le "
            "JOIN financial_transactions ft ON ft.financial_transaction_id=le.financial_transaction_id "
            "WHERE le.financial_account_id=? AND ft.financial_account_id=? "
            "AND le.currency_code=? AND ft.currency_code=? "
            "AND le.state='posted' AND ft.state='recognized' AND le.posted_at<=?",
            (str(account_id), str(account_id), currency, currency, cutoff),
        ).fetchone()
        return FinancialBalance(account_id, currency, int(row[0]), instant, int(row[1]))
