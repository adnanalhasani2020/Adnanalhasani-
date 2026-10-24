-- Harden journal posting against inactive accounts and currency mismatches.
-- This migration is intentionally additive and does not rewrite historical entries.
CREATE OR REPLACE FUNCTION public.commercial_post_journal_entry(p_entry_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_org uuid;
  v_status text;
  v_line_count integer;
  v_actor uuid := auth.uid();
BEGIN
  IF v_actor IS NULL THEN
    RAISE EXCEPTION 'Authenticated user required' USING ERRCODE = '42501';
  END IF;

  SELECT e.organization_id, e.status
    INTO v_org, v_status
  FROM public.commercial_journal_entries e
  WHERE e.id = p_entry_id
  FOR UPDATE;

  IF NOT FOUND OR v_org IS NULL THEN
    RAISE EXCEPTION 'Journal entry not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.commercial_organization_members m
    WHERE m.organization_id = v_org
      AND m.user_id = v_actor
      AND m.state = 'active'
      AND m.role IN ('owner', 'admin', 'manager')
  ) THEN
    RAISE EXCEPTION 'Insufficient permission to post journal entry' USING ERRCODE = '42501';
  END IF;

  IF v_status <> 'draft' THEN
    RAISE EXCEPTION 'Only draft entries can be posted' USING ERRCODE = '55000';
  END IF;

  SELECT count(*) INTO v_line_count
  FROM public.commercial_journal_lines l
  WHERE l.journal_entry_id = p_entry_id
    AND l.organization_id = v_org;

  IF v_line_count < 2 THEN
    RAISE EXCEPTION 'A journal entry needs at least two lines' USING ERRCODE = '23514';
  END IF;

  -- Account ownership is protected by composite foreign keys, but activity and
  -- currency must also be validated at the final posting boundary.
  IF EXISTS (
    SELECT 1
    FROM public.commercial_journal_lines l
    JOIN public.commercial_ledger_accounts a
      ON a.organization_id = l.organization_id
     AND a.id = l.ledger_account_id
    WHERE l.journal_entry_id = p_entry_id
      AND l.organization_id = v_org
      AND (
        a.is_active IS DISTINCT FROM true
        OR a.currency_code IS DISTINCT FROM l.currency_code
      )
  ) THEN
    RAISE EXCEPTION 'Journal entry contains an inactive account or account currency mismatch'
      USING ERRCODE = '23514';
  END IF;

  IF EXISTS (
    SELECT l.currency_code
    FROM public.commercial_journal_lines l
    WHERE l.journal_entry_id = p_entry_id
      AND l.organization_id = v_org
    GROUP BY l.currency_code
    HAVING sum(l.debit_minor) <> sum(l.credit_minor)
  ) THEN
    RAISE EXCEPTION 'Journal entry is not balanced per currency' USING ERRCODE = '23514';
  END IF;

  UPDATE public.commercial_journal_entries
  SET status = 'posted',
      posted_by = v_actor,
      posted_at = now()
  WHERE id = p_entry_id
    AND organization_id = v_org
    AND status = 'draft';

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Journal entry state changed; retry the operation' USING ERRCODE = '40001';
  END IF;

  RETURN true;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_post_journal_entry(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_post_journal_entry(uuid) FROM anon;
GRANT EXECUTE ON FUNCTION public.commercial_post_journal_entry(uuid) TO authenticated;
