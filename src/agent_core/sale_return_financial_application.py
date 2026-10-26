"""Governed partial Sale returns for settled invoices.

A financially linked return requires a completed external refund Payment, an
approved Finance action, an immutable credit note, and a posted negative ledger
entry before inventory is restored.
"""
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError, require_canonical_currency_code


class SaleReturnFinancialApplication:
    ACTION_CODE = "finance.sale_return.refund"

    @staticmethod
    def _uuid(value, label):
        try:
            return str(UUID(str(value)))
        except (TypeError, ValueError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _instant(value, label):
        if isinstance(value, datetime):
            result = value
        elif isinstance(value, str):
            try:
                result = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be an ISO timestamp") from exc
        else:
            raise ValidationError(f"{label} must be a timezone-aware timestamp")
        if result.tzinfo is None or result.utcoffset() is None:
            raise ValidationError(f"{label} must include a timezone")
        return result.astimezone(timezone.utc)

    @classmethod
    def _authorize(cls, db, *, return_event_id, context_ref,
                   authorization_grant_id, agent_action_id, now):
        context = cls._uuid(context_ref, "Finance context identifier")
        grant_id = cls._uuid(authorization_grant_id, "Finance AuthorizationGrant identifier")
        action_id = cls._uuid(agent_action_id, "Finance AgentAction identifier")
        grant = db.execute(
            "SELECT agent_id,action_code,scope_ref,context_ref,state,effective_from,effective_to "
            "FROM authorization_grants WHERE authorization_grant_id=?",
            (grant_id,),
        ).fetchone()
        if grant is None:
            raise ValidationError("A persisted Finance AuthorizationGrant is required")
        agent_id, action_code, scope, grant_context, state, starts, ends = grant
        if (agent_id is None or action_code != cls.ACTION_CODE or state != "active"
                or scope != return_event_id or grant_context != context):
            raise ValidationError("Finance grant must be active and scoped to this exact Sale return")
        instant = cls._instant(now, "Finance authorization timestamp")
        if cls._instant(starts, "Finance grant effective_from") > instant:
            raise ValidationError("Finance AuthorizationGrant is outside its effective period")
        if ends is not None and instant >= cls._instant(ends, "Finance grant effective_to"):
            raise ValidationError("Finance AuthorizationGrant is outside its effective period")
        action = db.execute(
            "SELECT agent_id,action_code,target_ref,context_ref,authorization_grant_id,state "
            "FROM agent_actions WHERE agent_action_id=?",
            (action_id,),
        ).fetchone()
        if action != (agent_id, cls.ACTION_CODE, return_event_id, context, grant_id, "approved"):
            raise ValidationError("Finance AgentAction does not match the approved Sale return")
        approval = db.execute(
            "SELECT approver_person_id FROM approvals WHERE agent_action_id=? "
            "AND state='APPROVED' AND approver_person_id IS NOT NULL "
            "ORDER BY decision_at DESC LIMIT 1",
            (action_id,),
        ).fetchone()
        if approval is None:
            raise ValidationError("Credit note issuance requires recorded human approval")
        approver = approval[0]
        if db.execute(
            "SELECT 1 FROM persons WHERE person_id=? AND state='active'", (approver,)
        ).fetchone() is None:
            raise ValidationError("Credit note approver must be an active Person")
        if db.execute(
            "SELECT 1 FROM agents WHERE agent_id=? AND state='active'", (agent_id,)
        ).fetchone() is None:
            raise ValidationError("Finance Agent must be active")
        return approver, context, grant_id, action_id

    def quote_return_amount(self, connection, *, return_event_id):
        """Return the locked, cumulative minor-unit allocation for a reserved return."""
        return_key = self._uuid(return_event_id, "Sale return event identifier")
        event = connection.execute(
            "SELECT sale_id,created_at FROM sale_return_events WHERE return_event_id=?",
            (return_key,),
        ).fetchone()
        if event is None:
            raise ValidationError("Sale return request does not exist")
        lines = connection.execute(
            "SELECT rl.sale_return_line_id,rl.sale_line_id,rl.quantity,l.quantity,l.line_total_minor,l.currency_code "
            "FROM sale_return_lines rl JOIN sale_lines l ON l.sale_line_id=rl.sale_line_id "
            "WHERE rl.return_event_id=? ORDER BY rl.sale_line_id",
            (return_key,),
        ).fetchall()
        if not lines:
            raise ValidationError("Sale return request has no lines")
        allocations = []
        for return_line_id, sale_line_id, return_qty, sold_qty, line_total, line_currency in lines:
            prior_qty = connection.execute(
                "SELECT COALESCE(SUM(rl.quantity),0) FROM sale_return_lines rl "
                "JOIN sale_return_events re ON re.return_event_id=rl.return_event_id "
                "WHERE rl.sale_line_id=? AND (re.created_at<? OR "
                "(re.created_at=? AND re.return_event_id<?))",
                (sale_line_id, event[1], event[1], return_key),
            ).fetchone()[0]
            amount = (line_total * (prior_qty + return_qty)) // sold_qty - (line_total * prior_qty) // sold_qty
            allocations.append({"sale_return_line_id": return_line_id, "quantity": return_qty,
                                "amount_minor": amount, "currency_code": line_currency})
        currency_codes = {row["currency_code"] for row in allocations}
        if len(currency_codes) != 1:
            raise ValidationError("A single credit note cannot mix currencies")
        return {"return_event_id": return_key, "sale_id": event[0],
                "refund_amount_minor": sum(row["amount_minor"] for row in allocations),
                "currency_code": next(iter(currency_codes)), "lines": allocations}

    def record_credit_note_for_return(
        self, connection, *, sale_id, return_event_id, refund_payment_id,
        credit_note_number, financial_account_id, finance_context_ref,
        finance_authorization_grant_id, finance_agent_action_id, now,
    ):
        sale_key = self._uuid(sale_id, "Sale identifier")
        return_key = self._uuid(return_event_id, "Sale return event identifier")
        refund_key = self._uuid(refund_payment_id, "Refund Payment identifier")
        account_key = self._uuid(financial_account_id, "Financial Account identifier")
        if not isinstance(credit_note_number, str) or not 1 <= len(credit_note_number.strip()) <= 120:
            raise ValidationError("Credit note number must contain 1 to 120 characters")
        number = credit_note_number.strip()
        timestamp = self._instant(now, "Credit note timestamp").isoformat(timespec="microseconds").replace("+00:00", "Z")
        approver, context, grant_id, action_id = self._authorize(
            connection, return_event_id=return_key, context_ref=finance_context_ref,
            authorization_grant_id=finance_authorization_grant_id,
            agent_action_id=finance_agent_action_id, now=timestamp,
        )

        event = connection.execute(
            "SELECT sale_id,created_at FROM sale_return_events WHERE return_event_id=?", (return_key,)
        ).fetchone()
        if event is None or event[0] != sale_key:
            raise ValidationError("Credit note must reference the exact persisted Sale return event")
        event_created_at = event[1]

        # Compute each line's cumulative minor-unit allocation with Python integer
        # arithmetic. Repeated partial returns therefore distribute every minor unit
        # exactly once and never lose rounding remainders.
        lines = connection.execute(
            "SELECT rl.sale_return_line_id,rl.sale_line_id,rl.quantity,l.quantity,l.line_total_minor,l.currency_code "
            "FROM sale_return_lines rl JOIN sale_lines l ON l.sale_line_id=rl.sale_line_id "
            "WHERE rl.return_event_id=? ORDER BY rl.sale_line_id",
            (return_key,),
        ).fetchall()
        if not lines:
            raise ValidationError("Credit note cannot be issued for a return without lines")
        allocations = []
        for return_line_id, sale_line_id, return_qty, sold_qty, line_total, line_currency in lines:
            prior_qty = connection.execute(
                "SELECT COALESCE(SUM(rl.quantity),0) FROM sale_return_lines rl "
                "JOIN sale_return_events re ON re.return_event_id=rl.return_event_id "
                "WHERE rl.sale_line_id=? AND (re.created_at<? OR "
                "(re.created_at=? AND re.return_event_id<?))",
                (sale_line_id, event_created_at, event_created_at, return_key),
            ).fetchone()[0]
            if prior_qty + return_qty > sold_qty:
                raise ValidationError("Cumulative returned quantity exceeds sold quantity")
            allocated = (line_total * (prior_qty + return_qty)) // sold_qty - (line_total * prior_qty) // sold_qty
            if allocated <= 0:
                raise ValidationError("Return allocation rounds to zero minor units; reconcile manually")
            allocations.append({
                "sale_return_line_id": return_line_id,
                "sale_line_id": sale_line_id,
                "quantity": return_qty,
                "amount_minor": allocated,
                "currency_code": require_canonical_currency_code(line_currency, label="Sale line currency"),
            })
        refund_amount = sum(line["amount_minor"] for line in allocations)
        currency = allocations[0]["currency_code"]
        if any(line["currency_code"] != currency for line in allocations):
            raise ValidationError("A single credit note cannot mix currencies")

        chain = connection.execute(
            "SELECT i.invoice_id,i.issuer_ref,i.state,i.obligation_id,"
            "s.buyer_person_id,s.state,"
            "o.creditor_person_id,o.debtor_person_id,o.amount_minor,o.currency_code,o.state,"
            "original.payment_id,original.amount_minor,original.currency_code,original.state,"
            "original.payer_person_id,original.payee_person_id,"
            "st.settlement_id,st.state,ft.financial_transaction_id,ft.financial_account_id,"
            "ft.amount_minor,ft.currency_code,ft.state,account.person_id,account.state,"
            "refund.amount_minor,refund.currency_code,refund.state,refund.payer_person_id,"
            "refund.payee_person_id,refund.invoice_id,refund.obligation_id,refund.provider_ref,"
            "refund.external_payment_ref "
            "FROM invoices i JOIN sales s ON s.sale_id=i.sale_id "
            "JOIN obligations o ON o.obligation_id=i.obligation_id "
            "JOIN settlements st ON st.obligation_id=o.obligation_id "
            "JOIN payments original ON original.payment_id=st.payment_id "
            "JOIN financial_transactions ft ON ft.settlement_id=st.settlement_id "
            "JOIN financial_accounts account ON account.financial_account_id=ft.financial_account_id "
            "JOIN payments refund ON refund.payment_id=? "
            "WHERE i.sale_id=? AND i.state='settled' AND st.state='settled' "
            "AND ft.state='recognized' AND ft.financial_account_id=? "
            "AND i.obligation_id=o.obligation_id AND o.state='satisfied' "
            "AND account.state='active'",
            (refund_key, sale_key, account_key),
        ).fetchall()
        if len(chain) != 1:
            raise ValidationError("A single settled Invoice, Settlement, and recognized Finance transaction are required")
        row = chain[0]
        (invoice_id, issuer, invoice_state, obligation_id, buyer, sale_state,
         creditor, debtor, obligation_amount, obligation_currency, obligation_state,
         original_payment_id, original_amount, original_currency, original_state,
         original_payer, original_payee, settlement_id, settlement_state,
         original_ft_id, original_account, original_ft_amount, original_ft_currency, original_ft_state,
         account_owner, account_state, refund_amount_actual, refund_currency, refund_state,
         refund_payer, refund_payee, refund_invoice, refund_obligation, provider_ref, external_ref) = row
        if sale_state not in {"completed", "fulfilled", "returned"}:
            raise ValidationError("Only a completed Commerce Sale can receive a credit note")
        if (issuer != creditor or buyer != debtor or original_payer != buyer or original_payee != issuer
                or original_state != "completed" or original_amount != obligation_amount
                or original_currency != obligation_currency or obligation_currency != currency
                or obligation_state != "satisfied" or original_account != account_key
                or original_ft_amount != original_amount or original_ft_currency != currency
                or original_ft_state != "recognized" or account_owner != issuer or account_state != "active"):
            raise ValidationError("Original Sale/Invoice/Payment/Settlement/Finance chain is inconsistent")
        if (refund_state != "completed" or refund_invoice is not None or refund_obligation is not None
                or refund_payer != issuer or refund_payee != buyer or refund_amount_actual != refund_amount
                or refund_currency != currency or not str(provider_ref or "").strip()
                or not str(external_ref or "").strip()):
            raise ValidationError("A completed refund Payment with exact amount, parties, currency, and provider references is required")

        prior_credits = connection.execute(
            "SELECT COALESCE(SUM(amount_minor),0) FROM credit_notes WHERE invoice_id=?",
            (invoice_id,),
        ).fetchone()[0]
        if prior_credits + refund_amount > original_amount:
            raise ValidationError("Cumulative credit notes exceed the original settled Payment")

        transaction_id, entry_id, credit_note_id = str(uuid4()), str(uuid4()), str(uuid4())
        connection.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
            "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
            "created_at,updated_at,provenance_id,version_no) "
            "VALUES(?,NULL,?, ?,?,'recognized','reversal',?,?,?, ?,NULL,1)",
            (transaction_id, account_key, -refund_amount, currency, return_key, timestamp, timestamp, timestamp),
        )
        sequence = connection.execute(
            "SELECT COALESCE(MAX(entry_sequence),0)+1 FROM ledger_entries WHERE financial_account_id=?",
            (account_key,),
        ).fetchone()[0]
        connection.execute(
            "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
            "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at,provenance_id) "
            "VALUES(?,?,?,?,?,?,'posted',?,?,?,NULL)",
            (entry_id, account_key, transaction_id, -refund_amount, currency, sequence,
             timestamp, timestamp, timestamp),
        )
        connection.execute(
            "INSERT INTO credit_notes(credit_note_id,return_event_id,sale_id,invoice_id,issuer_ref,"
            "credit_note_number,refund_payment_id,amount_minor,currency_code,financial_account_id,"
            "financial_transaction_id,ledger_entry_id,finance_context_ref,finance_authorization_grant_id,"
            "finance_agent_action_id,state,created_at,updated_at,version_no) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'issued',?,?,1)",
            (credit_note_id, return_key, sale_key, invoice_id, issuer, number, refund_key,
             refund_amount, currency, account_key, transaction_id, entry_id, context, grant_id,
             action_id, timestamp, timestamp),
        )
        for allocation in allocations:
            connection.execute(
                "INSERT INTO credit_note_lines(credit_note_line_id,credit_note_id,sale_return_line_id,"
                "quantity,amount_minor,currency_code,created_at) VALUES(?,?,?,?,?,?,?)",
                (str(uuid4()), credit_note_id, allocation["sale_return_line_id"],
                 allocation["quantity"], allocation["amount_minor"], currency, timestamp),
            )
        connection.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()), "finance", credit_note_id, "credit_note_issued", timestamp, approver, None,
             f"credit-note-state:{credit_note_id}:v1",
             f"return:{return_key};invoice:{invoice_id};refund:{refund_key};amount_minor:{refund_amount};"
             f"transaction:{transaction_id};ledger:{entry_id}", timestamp),
        )
        connection.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid4()), "finance", transaction_id, "financial_return_reversal_recognized", timestamp,
             approver, None, f"financial-transaction:{transaction_id}:v1",
             f"return:{return_key};credit_note:{credit_note_id};amount_minor:{-refund_amount};ledger:{entry_id}",
             timestamp),
        )
        return {
            "credit_note_id": credit_note_id, "credit_note_number": number,
            "refund_amount_minor": refund_amount, "refund_payment_id": refund_key,
            "financial_transaction_id": transaction_id, "ledger_entry_id": entry_id,
            "currency_code": currency,
        }
