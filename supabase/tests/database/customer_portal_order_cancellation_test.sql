-- Run with Supabase CLI / pgTAP after all migrations in a disposable database.
BEGIN;
SELECT plan(6);

SELECT has_function(
  'public',
  'commercial_customer_portal_cancel_order',
  ARRAY['uuid', 'text'],
  'customer cancellation RPC exists'
);

SELECT ok(
  (SELECT p.prosecdef
     AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=""']
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname = 'commercial_customer_portal_cancel_order'
     AND pg_get_function_identity_arguments(p.oid) = 'p_order_id uuid, p_cancellation_reason text'),
  'customer cancellation is security-definer with an empty pinned search_path'
);

SELECT ok(
  position('IS DISTINCT FROM ''unpaid''' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_order(uuid,text)'::regprocedure
  )) > 0
  AND position('IS DISTINCT FROM ''credit''' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_order(uuid,text)'::regprocedure
  )) > 0,
  'NULL or unknown payment states cannot pass the unpaid-order guard'
);

SELECT ok(
  NOT has_function_privilege('anon', 'public.commercial_customer_portal_cancel_order(uuid,text)', 'EXECUTE'),
  'anonymous users cannot execute customer cancellation'
);

SELECT ok(
  has_function_privilege('authenticated', 'public.commercial_customer_portal_cancel_order(uuid,text)', 'EXECUTE'),
  'authenticated customers can call the guarded cancellation RPC'
);

SELECT ok(
  position('FOR UPDATE' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_order(uuid,text)'::regprocedure
  )) > 0
  AND position('already_cancelled' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_order(uuid,text)'::regprocedure
  )) > 0,
  'cancellation row-locks the order and handles idempotent retries'
);

SELECT * FROM finish();
ROLLBACK;
