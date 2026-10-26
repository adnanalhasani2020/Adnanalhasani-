-- A paid Sale return must carry a credit note, a completed external refund Payment,
-- and an approved negative Finance ledger transaction before stock is restored.
CREATE TABLE credit_notes (
    credit_note_id TEXT PRIMARY KEY,
    return_event_id TEXT NOT NULL UNIQUE REFERENCES sale_return_events(return_event_id),
    sale_id TEXT NOT NULL REFERENCES sales(sale_id),
    invoice_id TEXT NOT NULL REFERENCES invoices(invoice_id),
    issuer_ref TEXT NOT NULL REFERENCES persons(person_id),
    credit_note_number TEXT NOT NULL CHECK (length(trim(credit_note_number)) BETWEEN 1 AND 120),
    refund_payment_id TEXT NOT NULL UNIQUE REFERENCES payments(payment_id),
    amount_minor INTEGER NOT NULL CHECK (typeof(amount_minor)='integer' AND amount_minor>0),
    currency_code TEXT NOT NULL CHECK (length(currency_code)=3),
    financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
    financial_transaction_id TEXT NOT NULL UNIQUE REFERENCES financial_transactions(financial_transaction_id),
    ledger_entry_id TEXT NOT NULL UNIQUE REFERENCES ledger_entries(ledger_entry_id),
    finance_context_ref TEXT NOT NULL,
    finance_authorization_grant_id TEXT NOT NULL REFERENCES authorization_grants(authorization_grant_id),
    finance_agent_action_id TEXT NOT NULL REFERENCES agent_actions(agent_action_id),
    state TEXT NOT NULL CHECK (state='issued'),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no>0),
    UNIQUE (issuer_ref,credit_note_number)
);

CREATE TABLE credit_note_lines (
    credit_note_line_id TEXT PRIMARY KEY,
    credit_note_id TEXT NOT NULL REFERENCES credit_notes(credit_note_id),
    sale_return_line_id TEXT NOT NULL REFERENCES sale_return_lines(sale_return_line_id),
    quantity INTEGER NOT NULL CHECK (typeof(quantity)='integer' AND quantity>0),
    amount_minor INTEGER NOT NULL CHECK (typeof(amount_minor)='integer' AND amount_minor>0),
    currency_code TEXT NOT NULL CHECK (length(currency_code)=3),
    created_at TEXT NOT NULL,
    UNIQUE (credit_note_id,sale_return_line_id)
);

CREATE INDEX ix_credit_notes_invoice ON credit_notes(invoice_id,created_at);
CREATE INDEX ix_credit_note_lines_return_line ON credit_note_lines(sale_return_line_id);

-- Credit-note amount is computed cumulatively per sold line. This allocates every
-- minor unit across repeated partial returns without losing or creating rounding cents.
CREATE TRIGGER credit_note_financial_integrity_guard
BEFORE INSERT ON credit_notes
WHEN NOT EXISTS (
    SELECT 1
    FROM sale_return_events re
    JOIN sales s ON s.sale_id=re.sale_id
    JOIN invoices i ON i.invoice_id=NEW.invoice_id AND i.sale_id=s.sale_id
    JOIN obligations o ON o.obligation_id=i.obligation_id
    JOIN settlements st ON st.obligation_id=o.obligation_id AND st.state='settled'
    JOIN payments original ON original.payment_id=st.payment_id
    JOIN financial_transactions original_ft ON original_ft.settlement_id=st.settlement_id
                                             AND original_ft.state='recognized'
    JOIN financial_accounts original_account ON original_account.financial_account_id=original_ft.financial_account_id
    JOIN payments refund ON refund.payment_id=NEW.refund_payment_id
    JOIN financial_transactions reversal ON reversal.financial_transaction_id=NEW.financial_transaction_id
    JOIN ledger_entries entry ON entry.ledger_entry_id=NEW.ledger_entry_id
    JOIN financial_accounts refund_account ON refund_account.financial_account_id=NEW.financial_account_id
    JOIN authorization_grants grant_row ON grant_row.authorization_grant_id=NEW.finance_authorization_grant_id
    JOIN agent_actions action_row ON action_row.agent_action_id=NEW.finance_agent_action_id
    WHERE re.return_event_id=NEW.return_event_id
      AND re.sale_id=NEW.sale_id
      AND i.invoice_id=NEW.invoice_id
      AND i.state='settled'
      AND i.issuer_ref=NEW.issuer_ref
      AND o.state='satisfied'
      AND o.creditor_person_id=NEW.issuer_ref
      AND s.buyer_person_id=original.payer_person_id
      AND original.payee_person_id=NEW.issuer_ref
      AND original.state='completed'
      AND original.amount_minor=o.amount_minor
      AND original.currency_code=o.currency_code
      AND original_ft.financial_account_id=NEW.financial_account_id
      AND original_account.person_id=NEW.issuer_ref
      AND refund.state='completed'
      AND refund.invoice_id IS NULL
      AND refund.obligation_id IS NULL
      AND refund.payer_person_id=NEW.issuer_ref
      AND refund.payee_person_id=s.buyer_person_id
      AND refund.amount_minor=NEW.amount_minor
      AND refund.currency_code=NEW.currency_code
      AND length(trim(COALESCE(refund.provider_ref,'')))>0
      AND length(trim(COALESCE(refund.external_payment_ref,'')))>0
      AND reversal.settlement_id IS NULL
      AND reversal.financial_account_id=NEW.financial_account_id
      AND reversal.amount_minor=-NEW.amount_minor
      AND reversal.currency_code=NEW.currency_code
      AND reversal.state='recognized'
      AND reversal.recognition_source_type='reversal'
      AND reversal.recognition_source_ref=NEW.return_event_id
      AND entry.financial_transaction_id=reversal.financial_transaction_id
      AND entry.financial_account_id=NEW.financial_account_id
      AND entry.amount_minor=-NEW.amount_minor
      AND entry.currency_code=NEW.currency_code
      AND entry.state='posted'
      AND refund_account.person_id=NEW.issuer_ref
      AND refund_account.state='active'
      AND grant_row.agent_id IS NOT NULL
      AND grant_row.action_code='finance.sale_return.refund'
      AND grant_row.scope_ref=NEW.return_event_id
      AND grant_row.context_ref=NEW.finance_context_ref
      AND grant_row.state='active'
      AND grant_row.effective_from<=NEW.created_at
      AND (grant_row.effective_to IS NULL OR grant_row.effective_to>NEW.created_at)
      AND action_row.agent_id=grant_row.agent_id
      AND action_row.action_code='finance.sale_return.refund'
      AND action_row.target_ref=NEW.return_event_id
      AND action_row.context_ref=NEW.finance_context_ref
      AND action_row.authorization_grant_id=NEW.finance_authorization_grant_id
      AND action_row.state='approved'
      AND EXISTS (
          SELECT 1 FROM agents ag
          WHERE ag.agent_id=action_row.agent_id AND ag.state='active'
      )
      AND EXISTS (
          SELECT 1 FROM approvals ap
          JOIN persons approver ON approver.person_id=ap.approver_person_id AND approver.state='active'
          WHERE ap.agent_action_id=action_row.agent_action_id
            AND ap.state='APPROVED' AND ap.approver_person_id IS NOT NULL
      )
      AND NEW.amount_minor = (
          SELECT COALESCE(SUM(
              CAST(l.line_total_minor * (
                  COALESCE((SELECT SUM(prior.quantity) FROM sale_return_lines prior
                            JOIN sale_return_events prior_event ON prior_event.return_event_id=prior.return_event_id
                            WHERE prior.sale_line_id=l.sale_line_id
                              AND prior.return_event_id<>re.return_event_id),0)
                  + rl.quantity
              ) / l.quantity AS INTEGER)
              - CAST(l.line_total_minor * COALESCE((
                  SELECT SUM(prior.quantity) FROM sale_return_lines prior
                  JOIN sale_return_events prior_event ON prior_event.return_event_id=prior.return_event_id
                  WHERE prior.sale_line_id=l.sale_line_id
                    AND prior.return_event_id<>re.return_event_id
              ),0) / l.quantity AS INTEGER)
          ),0)
          FROM sale_return_lines rl
          JOIN sale_lines l ON l.sale_line_id=rl.sale_line_id
          WHERE rl.return_event_id=re.return_event_id
      )
      AND NEW.amount_minor <= original.amount_minor - COALESCE((
          SELECT SUM(previous.amount_minor) FROM credit_notes previous
          WHERE previous.invoice_id=NEW.invoice_id
      ),0)
)
BEGIN
    SELECT RAISE(ABORT, 'Credit note requires a matching settled Invoice, completed refund Payment, approved Finance reversal, and exact cumulative return amount');
END;

CREATE TRIGGER credit_note_line_integrity_guard
BEFORE INSERT ON credit_note_lines
WHEN NOT EXISTS (
    SELECT 1
    FROM credit_notes cn
    JOIN sale_return_events re ON re.return_event_id=cn.return_event_id
    JOIN sale_return_lines rl ON rl.sale_return_line_id=NEW.sale_return_line_id
                              AND rl.return_event_id=re.return_event_id
    JOIN sale_lines l ON l.sale_line_id=rl.sale_line_id
    WHERE cn.credit_note_id=NEW.credit_note_id
      AND NEW.quantity=rl.quantity
      AND NEW.currency_code=l.currency_code
      AND NEW.amount_minor=(
          CAST(l.line_total_minor * (
              COALESCE((SELECT SUM(prior.quantity) FROM sale_return_lines prior
                        JOIN sale_return_events prior_event ON prior_event.return_event_id=prior.return_event_id
                        WHERE prior.sale_line_id=l.sale_line_id
                          AND prior.return_event_id<>re.return_event_id),0)
              + rl.quantity
          ) / l.quantity AS INTEGER)
          - CAST(l.line_total_minor * COALESCE((
              SELECT SUM(prior.quantity) FROM sale_return_lines prior
              JOIN sale_return_events prior_event ON prior_event.return_event_id=prior.return_event_id
              WHERE prior.sale_line_id=l.sale_line_id
                AND prior.return_event_id<>re.return_event_id
          ),0) / l.quantity AS INTEGER)
      )
      AND NEW.amount_minor <= l.line_total_minor - COALESCE((
          SELECT SUM(previous_line.amount_minor)
          FROM credit_note_lines previous_line
          JOIN credit_notes previous_note ON previous_note.credit_note_id=previous_line.credit_note_id
          JOIN sale_return_lines previous_return_line
            ON previous_return_line.sale_return_line_id=previous_line.sale_return_line_id
          WHERE previous_return_line.sale_line_id=l.sale_line_id
      ),0)
)
BEGIN
    SELECT RAISE(ABORT, 'Credit note line amount/quantity must match the cumulative return allocation');
END;

CREATE TRIGGER credit_note_immutable_update
BEFORE UPDATE ON credit_notes
BEGIN
    SELECT RAISE(ABORT, 'Credit notes are immutable; issue a correcting note instead');
END;
CREATE TRIGGER credit_note_immutable_delete
BEFORE DELETE ON credit_notes
BEGIN
    SELECT RAISE(ABORT, 'Credit notes are immutable; issue a correcting note instead');
END;
CREATE TRIGGER credit_note_line_immutable_update
BEFORE UPDATE ON credit_note_lines
BEGIN
    SELECT RAISE(ABORT, 'Credit note lines are immutable');
END;
CREATE TRIGGER credit_note_line_immutable_delete
BEFORE DELETE ON credit_note_lines
BEGIN
    SELECT RAISE(ABORT, 'Credit note lines are immutable');
END;

-- Permit stock compensation for a financially linked Sale only after the exact return
-- event has a complete credit note, completed refund Payment, and posted reversal entry.
DROP TRIGGER IF EXISTS inventory_sale_return_invoice_reconciliation_guard;
CREATE TRIGGER inventory_sale_return_invoice_reconciliation_guard
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type='sale_return'
 AND EXISTS (
    SELECT 1
    FROM sale_return_lines rl
    JOIN sale_return_events re ON re.return_event_id=rl.return_event_id
    JOIN invoices i ON i.sale_id=re.sale_id
    LEFT JOIN payments p
      ON p.invoice_id=i.invoice_id
      OR (i.obligation_id IS NOT NULL AND p.obligation_id=i.obligation_id)
    WHERE rl.sale_return_line_id=NEW.source_ref
      AND (i.state='issued' OR i.obligation_id IS NOT NULL OR p.payment_id IS NOT NULL)
      AND NOT EXISTS (
          SELECT 1
          FROM credit_notes cn
          JOIN payments refund ON refund.payment_id=cn.refund_payment_id AND refund.state='completed'
          JOIN financial_transactions ft ON ft.financial_transaction_id=cn.financial_transaction_id
                                         AND ft.state='recognized' AND ft.recognition_source_type='reversal'
          JOIN ledger_entries le ON le.ledger_entry_id=cn.ledger_entry_id
                                 AND le.state='posted' AND le.financial_transaction_id=ft.financial_transaction_id
          WHERE cn.return_event_id=re.return_event_id
            AND cn.state='issued'
            AND cn.amount_minor=(SELECT COALESCE(SUM(cnl.amount_minor),0)
                                 FROM credit_note_lines cnl WHERE cnl.credit_note_id=cn.credit_note_id)
      )
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale return requires explicit Invoice reconciliation first');
END;
