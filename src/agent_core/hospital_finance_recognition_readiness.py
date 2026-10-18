"""Read-only audit of mapped Hospital events through persisted Finance lifecycle records.

This report complements transport reconciliation: a source mapping is not proof of
settlement or ledger recognition. It never writes or repairs Finance records.
"""
from __future__ import annotations

import sqlite3
from typing import Any


_SCOPE = (
    "Read-only lifecycle visibility from Hospital intake mapping through Finance "
    "Obligation/Payment, Settlement, FinancialTransaction, and LedgerEntry. "
    "A reported ledger-present state is not a full accounting reconciliation."
)


def _one(db: sqlite3.Connection, sql: str, args: tuple[Any, ...]) -> Any:
    return db.execute(sql, args).fetchone()


def build_hospital_finance_recognition_readiness_report(
    finance_db: sqlite3.Connection,
) -> dict[str, Any]:
    """Report the persisted Finance stage for every mapped Hospital source event.

    The result deliberately excludes source payloads, patient data, payment
    references, and exception text. Conflicted mappings are surfaced before
    lifecycle status, but the report never changes or clears the mapping.
    """
    intake_rows = finance_db.execute(
        "SELECT external_event_intake_id, source_event_key, event_type, amount_minor, "
        "currency_code, status, finance_entity_type, finance_entity_id "
        "FROM external_event_intake ORDER BY first_seen_at, source_event_key"
    ).fetchall()
    events: list[dict[str, Any]] = []

    for row in intake_rows:
        event_key = row["source_event_key"] if isinstance(row, sqlite3.Row) else row[1]
        event_type = row["event_type"] if isinstance(row, sqlite3.Row) else row[2]
        amount_minor = row["amount_minor"] if isinstance(row, sqlite3.Row) else row[3]
        currency_code = row["currency_code"] if isinstance(row, sqlite3.Row) else row[4]
        status = row["status"] if isinstance(row, sqlite3.Row) else row[5]
        entity_type = row["finance_entity_type"] if isinstance(row, sqlite3.Row) else row[6]
        entity_id = row["finance_entity_id"] if isinstance(row, sqlite3.Row) else row[7]
        intake_id = row["external_event_intake_id"] if isinstance(row, sqlite3.Row) else row[0]

        conflicts = _one(
            finance_db,
            "SELECT 1 FROM external_event_intake_conflicts "
            "WHERE external_event_intake_id=? LIMIT 1",
            (intake_id,),
        )
        item: dict[str, Any] = {
            "source_event_key": event_key,
            "event_type": event_type,
            "finance_entity_type": entity_type,
            "finance_entity_id": entity_id,
            "disposition": "",
            "settlement_count": 0,
            "recognized_transaction_count": 0,
            "posted_ledger_entry_count": 0,
        }
        if conflicts:
            item["disposition"] = "source_conflict_requires_review"
            events.append(item)
            continue
        if status != "mapped":
            item["disposition"] = "source_not_mapped"
            events.append(item)
            continue

        expected_type = {
            "hospital.invoice.created": "obligation",
            "hospital.payment.received": "payment",
        }.get(event_type)
        if entity_type != expected_type:
            item["disposition"] = "mapped_entity_type_mismatch"
            events.append(item)
            continue

        if entity_type == "obligation":
            entity = _one(
                finance_db,
                "SELECT obligation_id, amount_minor, currency_code, state "
                "FROM obligations WHERE obligation_id=?",
                (entity_id,),
            )
            if entity is None:
                item["disposition"] = "mapped_finance_entity_missing"
                events.append(item)
                continue
            entity_amount = entity["amount_minor"] if isinstance(entity, sqlite3.Row) else entity[1]
            entity_currency = entity["currency_code"] if isinstance(entity, sqlite3.Row) else entity[2]
            entity_state = entity["state"] if isinstance(entity, sqlite3.Row) else entity[3]
            if entity_state == "cancelled":
                item["disposition"] = "finance_entity_cancelled"
                events.append(item)
                continue
            if entity_amount != amount_minor or entity_currency != currency_code:
                item["disposition"] = "source_finance_amount_or_currency_mismatch"
                events.append(item)
                continue
            settlements = finance_db.execute(
                "SELECT settlement_id, payment_id, state FROM settlements WHERE obligation_id=?",
                (entity_id,),
            ).fetchall()
        elif entity_type == "payment":
            entity = _one(
                finance_db,
                "SELECT payment_id, obligation_id, amount_minor, currency_code, state "
                "FROM payments WHERE payment_id=?",
                (entity_id,),
            )
            if entity is None:
                item["disposition"] = "mapped_finance_entity_missing"
                events.append(item)
                continue
            entity_amount = entity["amount_minor"] if isinstance(entity, sqlite3.Row) else entity[2]
            entity_currency = entity["currency_code"] if isinstance(entity, sqlite3.Row) else entity[3]
            entity_state = entity["state"] if isinstance(entity, sqlite3.Row) else entity[4]
            if entity_state in {"failed", "cancelled"}:
                item["disposition"] = "finance_entity_ineligible"
                events.append(item)
                continue
            if entity_amount != amount_minor or entity_currency != currency_code:
                item["disposition"] = "source_finance_amount_or_currency_mismatch"
                events.append(item)
                continue
            settlements = finance_db.execute(
                "SELECT settlement_id, payment_id, state FROM settlements WHERE payment_id=?",
                (entity_id,),
            ).fetchall()
        else:
            item["disposition"] = "mapped_entity_type_mismatch"
            events.append(item)
            continue

        item["settlement_count"] = len(settlements)
        if not settlements:
            item["disposition"] = "awaiting_settlement"
            events.append(item)
            continue
        settled = [s for s in settlements if (s["state"] if isinstance(s, sqlite3.Row) else s[2]) == "settled"]
        if not settled:
            item["disposition"] = "settlement_not_settled"
            events.append(item)
            continue

        transactions: list[Any] = []
        for settlement in settled:
            settlement_id = settlement["settlement_id"] if isinstance(settlement, sqlite3.Row) else settlement[0]
            transactions.extend(finance_db.execute(
                "SELECT financial_transaction_id, amount_minor, currency_code, state "
                "FROM financial_transactions WHERE settlement_id=?",
                (settlement_id,),
            ).fetchall())
        item["recognized_transaction_count"] = sum(
            1 for tx in transactions
            if (tx["state"] if isinstance(tx, sqlite3.Row) else tx[3]) == "recognized"
        )
        if not transactions:
            item["disposition"] = "settled_without_financial_transaction"
            events.append(item)
            continue
        recognized = [
            tx for tx in transactions
            if (tx["state"] if isinstance(tx, sqlite3.Row) else tx[3]) == "recognized"
        ]
        if not recognized:
            item["disposition"] = "financial_transaction_not_recognized"
            events.append(item)
            continue

        ledger_entries: list[Any] = []
        for tx in recognized:
            tx_id = tx["financial_transaction_id"] if isinstance(tx, sqlite3.Row) else tx[0]
            ledger_entries.extend(finance_db.execute(
                "SELECT ledger_entry_id, state FROM ledger_entries "
                "WHERE financial_transaction_id=?",
                (tx_id,),
            ).fetchall())
        item["posted_ledger_entry_count"] = sum(
            1 for entry in ledger_entries
            if (entry["state"] if isinstance(entry, sqlite3.Row) else entry[1]) == "posted"
        )
        if not ledger_entries:
            item["disposition"] = "recognized_transaction_without_ledger_entry"
        elif item["posted_ledger_entry_count"] == 0:
            item["disposition"] = "ledger_entry_not_posted"
        else:
            item["disposition"] = "ledger_entry_present"
        events.append(item)

    from collections import Counter
    counts = Counter(item["disposition"] for item in events)
    return {
        "scope": _SCOPE,
        "event_count": len(events),
        "by_disposition": dict(sorted(counts.items())),
        "events": events,
        "read_only": True,
    }
