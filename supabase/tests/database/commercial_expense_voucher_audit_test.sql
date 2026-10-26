-- Schema-level regression checks for the atomic expense voucher cancellation audit.
-- Run with the Supabase database test suite against a disposable local/staging database.
BEGIN;
SELECT plan(4);

SELECT has_trigger(
  'public',
  'commercial_expense_vouchers',
  'commercial_expense_voucher_cancellation_audit_trg',
  'cancellation updates append an audit event'
);

SELECT ok(
  (SELECT p.prosecdef
          AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, commercial_private']
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'commercial_private'
     AND p.proname = 'commercial_audit_expense_voucher_cancellation'
     AND pg_get_function_identity_arguments(p.oid) = ''),
  'audit trigger function is security-definer with a pinned search_path'
);

SELECT ok(
  NOT has_function_privilege(
    'authenticated',
    'commercial_private.commercial_audit_expense_voucher_cancellation()',
    'EXECUTE'
  ),
  'authenticated clients cannot call the internal audit trigger function directly'
);

SELECT ok(
  position(
    '''expense_voucher.cancelled''' IN pg_get_functiondef(
      'commercial_private.commercial_audit_expense_voucher_cancellation()'::regprocedure
    )
  ) > 0
  AND position(
    '''reversal_journal_entry_id''' IN pg_get_functiondef(
      'commercial_private.commercial_audit_expense_voucher_cancellation()'::regprocedure
    )
  ) > 0,
  'audit record includes cancellation action and reversal reference'
);

SELECT * FROM finish();
ROLLBACK;
