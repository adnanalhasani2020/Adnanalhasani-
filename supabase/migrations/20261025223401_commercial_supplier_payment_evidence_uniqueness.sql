-- A payment evidence reference may settle only one supplier payment per organization and method.
-- This protects against replaying the same bank transfer/check/cash voucher against another invoice.
CREATE UNIQUE INDEX IF NOT EXISTS commercial_supplier_payments_evidence_uq
  ON public.commercial_supplier_payments (organization_id, payment_method, lower(btrim(evidence_reference)));
