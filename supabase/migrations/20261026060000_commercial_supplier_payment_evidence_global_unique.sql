-- Payment proof identifiers are unique across all methods within one organization.
DROP INDEX IF EXISTS public.commercial_supplier_payments_evidence_uq;
CREATE UNIQUE INDEX commercial_supplier_payments_evidence_uq
  ON public.commercial_supplier_payments (organization_id, lower(btrim(evidence_reference)));
