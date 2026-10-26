-- Restrict accounting diagnostics to trusted server-side operators.
-- This view can reveal organization IDs and accounting exception details. It must not
-- be readable by public/anon/authenticated API clients, even when it currently returns no rows.
ALTER VIEW public.commercial_accounting_integrity_checks SET (security_invoker = true);

REVOKE ALL PRIVILEGES ON TABLE public.commercial_accounting_integrity_checks
  FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.commercial_accounting_integrity_checks TO service_role;
