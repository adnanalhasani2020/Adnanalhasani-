"""Read-only, currency-specific activity statements over recognized Finance ledger entries.

This report is an account-level ledger activity view. It is not a trial balance,
income statement, balance sheet, cash-flow statement, or bank reconciliation:
the current ledger has no chart-account mapping or double-entry debit/credit model.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID

from agent_core.shared import ValidationError, require_canonical_currency_code


@dataclass(frozen=True)
class FinancialLedgerActivityLine:
    ledger_entry_id: UUID
    financial_transaction_id: UUID
    posted_at: datetime
    recognized_at: datetime
    amount_minor: int
    recognition_source_type: str
    recognition_source_ref: str


@dataclass(frozen=True)
class FinancialLedgerActivityReport:
    financial_account_id: UUID
    currency_code: str
    start_at: datetime
    end_at: datetime
    opening_balance_minor: int
    lines: tuple[FinancialLedgerActivityLine, ...]
    closing_balance_minor: int

    @property
    def entry_count(self) -> int:
        return len(self.lines)

    @property
    def activity_total_minor(self) -> int:
        return sum(line.amount_minor for line in self.lines)


class FinancialLedgerActivityReportReader:
    """Build a deterministic statement without writing or repairing financial data.

    The period is [start_at, end_at): start inclusive, end exclusive. Opening
    balance includes eligible entries before start_at; activity lines include
    eligible entries within the period. Amounts are signed integer minor units
    and are never combined across currencies.
    """

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

    def read(self, connection, financial_account_id, currency_code, *, start_at, end_at):
        account_id = self._uuid(financial_account_id, "Financial Account identifier")
        currency = require_canonical_currency_code(
            currency_code, label="Report currency_code"
        )
        start = self._instant(start_at, "Report start_at")
        end = self._instant(end_at, "Report end_at")
        if start >= end:
            raise ValidationError("Report start_at must be earlier than end_at")
        start_text = start.isoformat().replace("+00:00", "Z")
        end_text = end.isoformat().replace("+00:00", "Z")

        if connection.execute(
            "SELECT 1 FROM financial_accounts WHERE financial_account_id=?",
            (str(account_id),),
        ).fetchone() is None:
            raise ValidationError("Financial Account does not exist")

        # Validate the whole history needed for the opening balance, not just
        # visible activity lines. Otherwise old corruption could yield a plausible
        # but incorrect opening balance.
        orphan = connection.execute(
            "SELECT 1 FROM ledger_entries le "
            "WHERE le.financial_account_id=? AND le.currency_code=? "
            "AND le.state='posted' AND le.posted_at<? "
            "AND NOT EXISTS (SELECT 1 FROM financial_transactions ft "
            "WHERE ft.financial_transaction_id=le.financial_transaction_id) LIMIT 1",
            (str(account_id), currency, end_text),
        ).fetchone()
        if orphan is not None:
            raise ValidationError("Posted Ledger Entry references a missing Financial Transaction")

        invalid = connection.execute(
            "SELECT 1 FROM ledger_entries le "
            "JOIN financial_transactions ft "
            "ON ft.financial_transaction_id=le.financial_transaction_id "
            "WHERE le.state='posted' AND le.posted_at<? "
            "AND (le.financial_account_id=? OR ft.financial_account_id=?) "
            "AND (le.currency_code=? OR ft.currency_code=?) "
            "AND (ft.state<>'recognized' "
            "OR le.financial_account_id<>ft.financial_account_id "
            "OR le.currency_code<>ft.currency_code) LIMIT 1",
            (end_text, str(account_id), str(account_id), currency, currency),
        ).fetchone()
        if invalid is not None:
            raise ValidationError(
                "Ledger Entry does not match a recognized Financial Transaction "
                "for the requested account and currency"
            )

        rows = connection.execute(
            "SELECT le.ledger_entry_id, le.financial_transaction_id, le.posted_at, "
            "le.amount_minor, ft.recognized_at, ft.recognition_source_type, "
            "ft.recognition_source_ref "
            "FROM ledger_entries le "
            "JOIN financial_transactions ft "
            "ON ft.financial_transaction_id=le.financial_transaction_id "
            "WHERE le.financial_account_id=? AND ft.financial_account_id=? "
            "AND le.currency_code=? AND ft.currency_code=? "
            "AND le.state='posted' AND ft.state='recognized' AND le.posted_at<? "
            "ORDER BY le.posted_at ASC, le.entry_sequence ASC, le.ledger_entry_id ASC",
            (str(account_id), str(account_id), currency, currency, end_text),
        ).fetchall()

        opening = sum(int(row[3]) for row in rows if row[2] < start_text)
        lines = tuple(
            FinancialLedgerActivityLine(
                ledger_entry_id=UUID(row[0]),
                financial_transaction_id=UUID(row[1]),
                posted_at=self._instant(row[2], "Ledger Entry posted_at"),
                recognized_at=self._instant(row[4], "Financial Transaction recognized_at"),
                amount_minor=int(row[3]),
                recognition_source_type=row[5],
                recognition_source_ref=row[6],
            )
            for row in rows
            if start_text <= row[2] < end_text
        )
        activity = sum(line.amount_minor for line in lines)
        return FinancialLedgerActivityReport(
            financial_account_id=account_id,
            currency_code=currency,
            start_at=start,
            end_at=end,
            opening_balance_minor=opening,
            lines=lines,
            closing_balance_minor=opening + activity,
        )
