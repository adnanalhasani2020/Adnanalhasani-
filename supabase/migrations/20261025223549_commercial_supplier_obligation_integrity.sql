-- Keep the supplier payable subledger internally consistent even if future write paths are added.
ALTER TABLE public.commercial_supplier_obligations
  ADD CONSTRAINT commercial_supplier_obligation_state_consistency_ck CHECK (
    (amount_due_minor = 0 AND amount_paid_minor = 0 AND state = 'paid' AND journal_entry_id IS NULL)
    OR
    (amount_due_minor > 0 AND journal_entry_id IS NOT NULL AND (
      (state = 'open' AND amount_paid_minor = 0)
      OR (state = 'partially_paid' AND amount_paid_minor > 0 AND amount_paid_minor < amount_due_minor)
      OR (state = 'paid' AND amount_paid_minor = amount_due_minor)
    ))
  ),
  ADD CONSTRAINT commercial_supplier_obligation_payment_identity_uq
    UNIQUE (organization_id, id, supplier_invoice_id, currency_code);

ALTER TABLE public.commercial_supplier_payments
  ADD CONSTRAINT commercial_supplier_payment_obligation_identity_fk
  FOREIGN KEY (organization_id, obligation_id, supplier_invoice_id, currency_code)
  REFERENCES public.commercial_supplier_obligations(organization_id, id, supplier_invoice_id, currency_code)
  ON DELETE RESTRICT;
