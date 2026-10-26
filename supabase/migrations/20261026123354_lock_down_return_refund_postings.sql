-- Refund postings are internal accounting artifacts; all client access remains denied.
-- Explicit deny policy documents the intended boundary and removes ambiguous RLS-without-policy lint.
REVOKE ALL ON TABLE public.commercial_return_refund_postings FROM PUBLIC, anon, authenticated;
DROP POLICY IF EXISTS commercial_return_refund_postings_deny_client_access ON public.commercial_return_refund_postings;
CREATE POLICY commercial_return_refund_postings_deny_client_access
  ON public.commercial_return_refund_postings
  FOR ALL TO anon, authenticated
  USING (false)
  WITH CHECK (false);
